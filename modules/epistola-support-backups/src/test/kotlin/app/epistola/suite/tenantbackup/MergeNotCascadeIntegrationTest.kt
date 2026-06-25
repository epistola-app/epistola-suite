package app.epistola.suite.tenantbackup

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Proves the cascade-safety guarantee: a generated document pinned (FK `ON DELETE CASCADE`) to a
 * published `template_versions` row **survives** a restore. The merge upserts that version row
 * (never deletes it), so the document is untouched — where a naive purge-and-reimport would have
 * cascade-deleted all document history.
 */
class MergeNotCascadeIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Test
    fun `a document pinned to an unchanged version survives restore`() {
        val tenant = createTenant("Cascade")
        val tenantId = TenantId(tenant.id)
        val main = CatalogKey.of("main")
        val catalogId = CatalogId(main, tenantId)
        val templateKey = TestIdHelpers.nextTemplateId()
        val variantKey = "initial"

        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = main, name = "Main").execute()
            CreateDocumentTemplate(id = TemplateId(templateKey, catalogId), name = "Invoice").execute()
            val defaultVariant = VariantId(VariantKey.of(variantKey), TemplateId(templateKey, catalogId))
            PublishVersion(versionId = VersionId(VersionKey.of(1), defaultVariant)).execute()
        }

        // A generated document pinned to the published version (documents are NOT backed up).
        val documentId = UUID.randomUUID()
        jdbi.useHandle<Exception> { h ->
            h
                .createUpdate(
                    "INSERT INTO documents (id, tenant_key, catalog_key, template_key, variant_key, version_key, " +
                        "filename, size_bytes) VALUES (:id, :tk, 'main', :template, :variant, 1, 'invoice.pdf', 100)",
                ).bind("id", documentId)
                .bind("tk", tenant.id.value)
                .bind("template", templateKey.value)
                .bind("variant", variantKey)
                .execute()
        }

        val backup = withMediator { BuildTenantBackup(tenant.id).execute()!! }
        withMediator { RestoreTenantBackup(tenant.id, backup.bytes).execute() }

        val surviving =
            jdbi.withHandle<Int, Exception> { h ->
                h
                    .createQuery("SELECT count(*) FROM documents WHERE id = :id")
                    .bind("id", documentId)
                    .mapTo(Int::class.java)
                    .one()
            }
        assertThat(surviving).isEqualTo(1)
    }
}

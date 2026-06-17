package app.epistola.suite.tenantbackup

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The headline faithful-backup round-trip: build a backup, diverge the tenant, restore, and assert
 * the tenant is reproduced exactly — including **exact template version numbers** (the property the
 * catalog-export restore loses by renumbering) — while post-backup divergence is dropped and the
 * default-theme pointer is re-applied across the `tenants`↔`themes` cycle.
 */
class TenantBackupRoundTripIntegrationTest : IntegrationTestBase() {
    @Autowired
    lateinit var jdbi: Jdbi

    @Test
    fun `restore reproduces tenant, preserves version numbers, and drops divergence`() {
        val tenant = createTenant("Backup RT")
        val tenantId = TenantId(tenant.id)
        val main = CatalogKey.of("main")
        val catalogId = CatalogId(main, tenantId)

        val templateKey = TestIdHelpers.nextTemplateId()
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = main, name = "Main").execute()
            CreateDocumentTemplate(id = TemplateId(templateKey, catalogId), name = "Invoice").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), catalogId), name = "Brand").execute()
            SetTenantDefaultTheme(tenantId = tenant.id, themeId = ThemeKey.of("brand"), catalogKey = main).execute()

            // Publish the auto-created default version (v1) so it is a published row with a fixed number.
            val defaultVariant = VariantId(VariantKey.of("${templateKey.value}-default"), TemplateId(templateKey, catalogId))
            PublishVersion(versionId = VersionId(VersionKey.of(1), defaultVariant)).execute()
        }

        val backup = withMediator { BuildTenantBackup(tenant.id).execute() }
        val originalFingerprint = backup.fingerprint
        assertThat(backup.tableCount).isGreaterThan(0)

        // Diverge: a stray catalog, a stray template in `main`, and the default theme re-pointed.
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("stray"), name = "Stray").execute()
            CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), catalogId), name = "Stray Template").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("other"), catalogId), name = "Other").execute()
            SetTenantDefaultTheme(tenantId = tenant.id, themeId = ThemeKey.of("other"), catalogKey = main).execute()
        }

        val restore = withMediator { RestoreTenantBackup(tenant.id, backup.bytes).execute() }
        assertThat(restore.rowsRestored).isGreaterThan(0)

        withMediator {
            // Divergence dropped.
            val catalogs = ListCatalogs(tenant.id).query().map { it.id.value }
            assertThat(catalogs).contains("main").doesNotContain("stray")

            // Default theme re-applied across the cycle.
            val restored = GetTenant(tenant.id).query()
            assertThat(restored!!.defaultThemeKey?.value).isEqualTo("brand")
            assertThat(restored.defaultThemeCatalogKey?.value).isEqualTo("main")

            // Content-identical to the backup.
            assertThat(BuildTenantBackup(tenant.id).execute().fingerprint).isEqualTo(originalFingerprint)
        }

        // Exact version number preserved, and the stray template's versions are gone.
        val templates =
            jdbi.withHandle<List<String>, Exception> { h ->
                h
                    .createQuery("SELECT name FROM document_templates WHERE tenant_key = :tk ORDER BY name")
                    .bind("tk", tenant.id.value)
                    .mapTo(String::class.java)
                    .list()
            }
        assertThat(templates).containsExactly("Invoice")

        val publishedVersionIds =
            jdbi.withHandle<List<Int>, Exception> { h ->
                h
                    .createQuery(
                        "SELECT id FROM template_versions WHERE tenant_key = :tk AND status = 'published' ORDER BY id",
                    ).bind("tk", tenant.id.value)
                    .mapTo(Int::class.java)
                    .list()
            }
        assertThat(publishedVersionIds).containsExactly(1)
    }
}

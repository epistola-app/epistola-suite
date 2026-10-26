// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalogReleaseDetail
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DeleteCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.DeleteDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.DeleteVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * A template's variants are rows of their own in `release_resources` (storage type `variant`, ADR
 * 0033): each names its template's row in the same draft or release, goes when that row or its
 * draft goes, is frozen with its template in a release, and is no entry of a manifest and no
 * resource of the identity registry.
 *
 * Reads the table on purpose: the integrity is the database's, and no query shows a variant row
 * apart from its template.
 */
class TemplateVariantRowsTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")

    private fun variants(tenant: TenantKey, where: String = "TRUE"): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        handle.createQuery(
            "SELECT COALESCE((SELECT rv.version FROM catalog_versions rv WHERE rv.version_id = release_resources.version_id AND rv.state = 'released'), 'draft') || '/' || resource_key FROM release_resources WHERE tenant_key = :t AND resource_type = 'variant' AND $where ORDER BY 1",
        ).bind("t", tenant).mapTo(String::class.java).list()
    }

    @Test
    fun `a template's variants are rows of their own, frozen with its release, and go with it`() {
        val tenant = createTenant("Variant rows").id
        val catalog = testCatalog(tenant, letters)
        val template = TemplateId(TemplateKey.of("invoice"), catalog)
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()
            CreateVariant(VariantId(VariantKey.of("second"), template), "Second", "For courts").execute()
            mediator.releaseNext(catalog)
        }
        assertThat(variants(tenant)).containsExactly("1.0.0/initial", "1.0.0/second", "draft/initial", "draft/second")
        // The release names the contents its draft does, variant by variant.
        val divergent = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery(
                """
                SELECT count(*) FROM release_resources d
                JOIN release_resources r ON r.tenant_key = d.tenant_key AND r.resource_type = 'variant' AND r.parent_id = d.parent_id
                 AND r.resource_key = d.resource_key AND r.version_id IN (SELECT rv.version_id FROM catalog_versions rv WHERE rv.state = 'released' AND rv.version = '1.0.0')
                WHERE d.tenant_key = :t AND d.resource_type = 'variant' AND release_resource_is_draft(d.tenant_key, d.version_id) AND d.content_hash <> r.content_hash
                """,
            ).bind("t", tenant).mapTo(Int::class.java).one()
        }
        assertThat(divergent).isZero()

        withMediator { DeleteVariant(VariantId(VariantKey.of("second"), template)).execute() }
        assertThat(variants(tenant)).`as`("a deleted variant leaves the release's").containsExactly("1.0.0/initial", "1.0.0/second", "draft/initial")

        withMediator { DeleteDocumentTemplate(template).execute() }
        assertThat(variants(tenant, "release_resource_is_draft(tenant_key, version_id)")).`as`("a deleted template takes its variants").isEmpty()
        assertThat(variants(tenant)).`as`("the release keeps its own").containsExactly("1.0.0/initial", "1.0.0/second")
    }

    @Test
    fun `a manifest and the identity registry hold the template and no variant`() {
        val tenant = createTenant("Variant rows manifest").id
        val catalog = testCatalog(tenant, letters)
        val template = TemplateId(TemplateKey.of("invoice"), catalog)
        val version = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()
            CreateVariant(VariantId(VariantKey.of("second"), template), "Second", null).execute()
            mediator.releaseNext(catalog)
        }
        withMediator {
            val detail = GetCatalogReleaseDetail(catalog, version).query()!!
            assertThat(detail.resources.map { it.resourceType }).`as`("the release's entries").doesNotContain("variant").contains("template")
            val summary = ListCatalogReleaseSummaries(catalog).query().single { it.version == version }
            assertThat(summary.resourceCount).isEqualTo(detail.resources.size)
        }
        val registered = jdbi.withHandle<Int, Exception> { handle ->
            handle.createQuery("SELECT count(*) FROM catalog_resources WHERE tenant_key = :t AND resource_type = 'variant'").bind("t", tenant).mapTo(Int::class.java).one()
        }
        assertThat(registered).`as`("variants registered as catalog resources").isZero()
    }

    @Test
    fun `a draft's variant rows are copied with it and go with it`() {
        val tenant = createTenant("Variant rows draft").id
        val catalog = testCatalog(tenant, letters)
        val template = TemplateId(TemplateKey.of("invoice"), catalog)
        val work = withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()
            mediator.releaseNext(catalog)
            CreateCatalogDraft(catalog, name = "Redesign").execute()
        }
        assertThat(variants(tenant, "release_resource_is_draft(tenant_key, version_id)")).`as`("the new draft holds its template's variants too").containsExactly("draft/initial", "draft/initial")

        withMediator { DeleteCatalogDraft(catalog, DraftRef.Id(requireNotNull(work.versionId))).execute() }
        assertThat(variants(tenant, "release_resource_is_draft(tenant_key, version_id)")).`as`("a discarded draft takes them").containsExactly("draft/initial")
    }

    @Test
    fun `a variant row belongs to its template's row in its version`() {
        val tenant = createTenant("Variant rows parent").id
        val catalog = testCatalog(tenant, letters)
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice").execute()
        }
        assertThatThrownBy {
            jdbi.useTransaction<Exception> { handle ->
                // Raw SQL on purpose: no command parts a variant from its template.
                handle.execute("UPDATE release_resources SET parent_id = gen_random_uuid() WHERE tenant_key = ? AND resource_type = 'variant'", tenant.value)
            }
        }.hasStackTraceContaining("has no template row in its version")
    }
}

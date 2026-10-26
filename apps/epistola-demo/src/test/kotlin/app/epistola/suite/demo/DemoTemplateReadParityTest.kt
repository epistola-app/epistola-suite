// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Every template of the demo catalog reads the same from its next version and from the release it
 * was installed as (ADR 0033, steps 7a and 7b): its fields, its theme, its data contract, its variants
 * and each variant's model, the default variant's included (a release keeps that one on the
 * template). Installed from one archive in the released form, the draft and the release name the
 * same content, and `V20261025170700` converts each template, from its 1.x columns, exactly as the
 * suite stores it.
 */
class DemoTemplateReadParityTest : IntegrationTestBase() {

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var jdbi: org.jdbi.v3.core.Jdbi

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var objectMapper: tools.jackson.databind.ObjectMapper

    @org.springframework.beans.factory.annotation.Autowired
    private lateinit var templates: app.epistola.suite.templates.TemplateStore

    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"

    @Test
    fun `every demo template reads the same from the next version and from its release`() {
        val tenant = createTenant("Demo template parity").id
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant, sourceUrl = demoUrl).execute() }
        val catalog = testCatalog(TenantId(tenant), CatalogKey.of("epistola-demo"))
        val release = withMediator { ListCatalogReleases(catalog).query() }.first { it.status == CatalogReleaseStatus.RELEASED }
        val released = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))

        fun DocumentTemplate.fields() = listOf(id, catalogKey, namespace, name, description, themeKey, themeCatalogKey, themeCatalogNamespace, pdfaEnabled)
        fun TemplateVariant.fields() = listOf(id, catalogKey, templateKey, title, description, attributes, isDefault)

        val drafted = withMediator { ListDocumentTemplates(TenantId(tenant), catalog = catalog, limit = 500).query() }.sortedBy { it.id.value }
        val releasedTemplates = withMediator { ListDocumentTemplates(TenantId(tenant), catalog = catalog, limit = 500, catalogVersion = released).query() }.sortedBy { it.id.value }
        assertThat(drafted).isNotEmpty()
        assertThat(releasedTemplates.map { it.fields() }).isEqualTo(drafted.map { it.fields() })

        withMediator {
            for (template in drafted) {
                val id = TemplateId(template.id, catalog)
                assertThat(GetDocumentTemplate(id, released).query()?.fields()).`as`(template.id.value).isEqualTo(template.fields())

                val draftContract = GetDataContract(id).query()!!
                val releasedContract = GetDataContract(id, released).query()!!
                assertThat(releasedContract.dataModel).`as`("${template.id} data model").isEqualTo(draftContract.dataModel)
                assertThat(releasedContract.dataExamples).`as`("${template.id} data examples").isEqualTo(draftContract.dataExamples)

                val draftVariants = ListVariants(id).query()
                val releasedVariants = ListVariants(id, released).query()
                assertThat(releasedVariants.map { it.fields() }).`as`("${template.id} variants").isEqualTo(draftVariants.map { it.fields() })
                for (variant in draftVariants) {
                    val variantId = VariantId(variant.id, id)
                    assertThat(GetVariantContent(variantId, released).query())
                        .`as`("${template.id}/${variant.id} model")
                        .isEqualTo(GetVariantContent(variantId).query())
                }
            }
        }
        // Installed from one archive in the released form, the draft and the release name the same content.
        val divergent = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery(
                """
                -- Per row: each template's, and each of its variants' (rows of their own).
                SELECT d.resource_type || '/' || d.resource_key FROM release_resources d
                JOIN release_resources r ON r.tenant_key = d.tenant_key AND r.catalog_id = d.catalog_id
                  AND r.resource_type = d.resource_type AND r.resource_key = d.resource_key AND r.parent_id IS NOT DISTINCT FROM d.parent_id
                  AND r.version = :v
                WHERE d.tenant_key = :t AND d.version_id IS NOT NULL AND d.resource_type IN ('template', 'variant') AND d.content_hash <> r.content_hash
                """,
            ).bind("t", tenant).bind("v", release.version).mapTo(String::class.java).list()
        }
        assertThat(divergent).isEmpty()
        app.epistola.suite.testing.TemplateStorageConversion.verify(jdbi, objectMapper, templates, catalog)
    }
}

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
import app.epistola.suite.stencils.model.StencilInstances
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
 * was installed as (ADR 0033, step 7a): its fields, its theme, its data contract, its variants and
 * each variant's model, the default variant's included (a release keeps that one on the template).
 *
 * What a draft keeps that the wire form does not, and so a release cannot give back, is left out:
 * timestamps and revisions, a variant's description, and a data example's id (a release reads its
 * key in its place).
 */
class DemoTemplateReadParityTest : IntegrationTestBase() {

    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"

    @Test
    fun `every demo template reads the same from the next version and from its release`() {
        val tenant = createTenant("Demo template parity").id
        withMediator { EnsureSubscribedCatalog(tenantKey = tenant, sourceUrl = demoUrl).execute() }
        val catalog = testCatalog(TenantId(tenant), CatalogKey.of("epistola-demo"))
        val release = withMediator { ListCatalogReleases(catalog).query() }.first { it.status == CatalogReleaseStatus.RELEASED }
        val released = CatalogReleaseRef(catalog, DraftRef.Released(requireNotNull(release.version)))

        fun DocumentTemplate.fields() = listOf(id, catalogKey, namespace, name, description, themeKey, themeCatalogKey, themeCatalogNamespace, pdfaEnabled)
        fun TemplateVariant.fields() = listOf(id, catalogKey, templateKey, title, attributes, isDefault)

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
                assertThat(releasedContract.dataExamples.map { listOf(it.name, it.data) })
                    .`as`("${template.id} data examples").isEqualTo(draftContract.dataExamples.map { listOf(it.name, it.data) })

                val draftVariants = ListVariants(id).query()
                val releasedVariants = ListVariants(id, released).query()
                assertThat(releasedVariants.map { it.fields() }).`as`("${template.id} variants").isEqualTo(draftVariants.map { it.fields() })
                for (variant in draftVariants) {
                    val variantId = VariantId(variant.id, id)
                    // The archive carries its stencil instances as the contract writes them; a draft
                    // stores them collapsed (contract ADR 0006), which is the same document.
                    assertThat(GetVariantContent(variantId, released).query()?.let(StencilInstances::collapse))
                        .`as`("${template.id}/${variant.id} model")
                        .isEqualTo(GetVariantContent(variantId).query())
                }
            }
        }
    }
}

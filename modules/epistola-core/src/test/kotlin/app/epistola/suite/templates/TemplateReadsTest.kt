// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.declareDependency
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.contracts.queries.GetDataContract
import app.epistola.suite.templates.queries.CountDocumentTemplates
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A release's templates read through the template queries as a draft's do (ADR 0033, step 7a), and
 * a model reads with its references into its own catalog named with that catalog, however it is
 * stored.
 */
class TemplateReadsTest : IntegrationTestBase() {

    private fun model(themeKey: String, themeCatalog: String?) = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = emptyList())),
        themeRef = ThemeRefOverride(themeKey = themeKey, catalogKey = themeCatalog),
    )

    @Test
    fun `a release's template reads as it was released, after its draft moved on`() {
        val tenant = createTenant("Template reads").id
        val shared = testCatalog(tenant, CatalogKey.of("shared"))
        val letters = testCatalog(tenant, CatalogKey.of("letters"))
        val template = TemplateId(TemplateKey.of("invoice"), letters)
        val default = VariantId(VariantKey.INITIAL, template)
        val second = VariantId(VariantKey.of("second"), template)
        withMediator {
            CreateCatalog(tenant, shared.key, "Shared").execute()
            CreateCatalog(tenant, letters.key, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), shared), "Brand").execute()
            CreateTheme(ThemeId(ThemeKey.of("house"), letters), "House").execute()
            mediator.releaseNext(shared)
            declareDependency(tenant, letters.key, on = shared.key)
            CreateDocumentTemplate(template, "Invoice", description = "Bills").execute().withRequiredDataExample()
            UpdateDocumentTemplate(template, themeId = ThemeKey.of("brand"), themeCatalog = shared).execute()
            CreateVariant(second, "Second", null, mapOf()).execute()
            // Relative, as an author may write it, and named with its catalog, as the editor does.
            UpdateVariantContent(default, model("house", null)).execute()
            UpdateVariantContent(second, model("house", "letters")).execute()
        }
        val version = withMediator { mediator.releaseNext(letters) }
        val released = CatalogReleaseRef(letters, DraftRef.Released(version))
        val before = withMediator { listOf(GetDocumentTemplate(template).query(), ListVariants(template).query().map { it.id }, GetDataContract(template).query()?.dataExamples?.map { it.name }) }

        withMediator {
            UpdateDocumentTemplate(template, name = "Invoice, renamed", clearThemeId = true).execute()
            UpdateVariantContent(default, model("brand", "shared")).execute()
        }

        withMediator {
            val read = GetDocumentTemplate(template, released).query()!!
            assertThat(listOf(read.name, read.description, read.themeKey?.value, read.themeCatalogKey?.value)).containsExactly("Invoice", "Bills", "brand", "shared")
            assertThat(read.themeCatalogNamespace).isEqualTo((before[0] as DocumentTemplate).themeCatalogNamespace)
            assertThat(ListVariants(template, released).query().map { it.id }).isEqualTo(before[1])
            assertThat(GetVariant(second, released).query()?.title).isEqualTo("Second")
            assertThat(GetDataContract(template, released).query()?.dataExamples?.map { it.name }).isEqualTo(before[2])
            // Both models name the theme of their own catalog with it, the default variant's included.
            for (variant in listOf(default, second)) {
                assertThat((GetVariantContent(variant, released).query()?.themeRef as ThemeRefOverride).catalogKey).`as`(variant.key.value).isEqualTo("letters")
            }
            assertThat(GetVariantContent(default).query()?.themeRef).isEqualTo(ThemeRefOverride(themeKey = "brand", catalogKey = "shared"))
            assertThat(ListDocumentTemplates(TenantId(tenant), catalog = letters, catalogVersion = released).query().map { it.name }).containsExactly("Invoice")
            assertThat(CountDocumentTemplates(TenantId(tenant), catalog = letters, catalogVersion = released).query()).isEqualTo(1)
            assertThat(GetDocumentTemplate(template).query()?.name).isEqualTo("Invoice, renamed")
        }
    }
}

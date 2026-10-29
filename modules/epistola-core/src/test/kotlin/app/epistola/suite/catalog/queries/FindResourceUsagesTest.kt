// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.relocation.RelocationTestSupport
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What uses a catalog's resources from other catalogs (the **Used by** counts and dialog): every kind
 * of reference, read from the one reference authority, and nothing a catalog uses of itself.
 */
class FindResourceUsagesTest : RelocationTestSupport() {

    @Test
    fun `every kind of use from another catalog counts, and a catalog's own use does not`() {
        val tenant = tenantWith("usages-every-kind")
        withMediator {
            importFont(tenant, shared, "brandfont")
            CreateTheme(ThemeId(ThemeKey.of("corporate"), catalogId(tenant, shared)), "Corporate").execute()
            CreateTheme(ThemeId(ThemeKey.of("plain"), catalogId(tenant, letters)), "Plain", documentStyles = fontStyle("brandfont", shared.value)).execute()
            CreateCodeList(CodeListId(CodeListKey.of("langs"), catalogId(tenant, shared)), displayName = "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands"))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("lang"), catalogId(tenant, shared)), "Language", codeListId = CodeListId(CodeListKey.of("langs"), catalogId(tenant, shared))).execute()
            CreateStencil(StencilId(StencilKey.of("seal"), catalogId(tenant, shared)), "Seal").execute()
            CreateStencil(StencilId(StencilKey.of("footer"), catalogId(tenant, shared)), "Footer", content = templateEmbedding("seal")).execute()

            val invoice = TemplateId(TemplateKey.of("invoice"), catalogId(tenant, letters))
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
            UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("corporate"), themeCatalog = catalogId(tenant, shared)).execute()
            UpdateVariant(VariantId(VariantKey.INITIAL, invoice), "Default", mapOf("shared.lang" to "nl")).execute()
        }

        val usages = withMediator { FindResourceUsages(catalogId(tenant, shared)).query() }

        assertThat(usages["theme:corporate"]).containsExactly(ResourceUsage("Invoice", "letters", "template"))
        assertThat(usages["font:brandfont"]).describedAs("a font another catalog's theme uses").containsExactly(ResourceUsage("Plain", "letters", "theme"))
        assertThat(usages["attribute:lang"]).describedAs("an attribute another catalog's variant selects by").containsExactly(ResourceUsage("Invoice", "letters", "template"))
        // Shared's own uses (an attribute's code list, a stencil in a stencil) are no outside use.
        assertThat(usages).doesNotContainKeys("codeList:langs", "stencil:seal")
    }
}

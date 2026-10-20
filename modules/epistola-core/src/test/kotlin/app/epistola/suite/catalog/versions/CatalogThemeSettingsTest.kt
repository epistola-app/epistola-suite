// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.versions

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.generation.PrepareDraftRender
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/** A catalog version's own default theme and declared presets (contract ADR 0007). */
class CatalogThemeSettingsTest : IntegrationTestBase() {

    private val letters = CatalogKey.of("letters")

    private fun lettersIn(name: String): CatalogId {
        val tenant = createTenant(name)
        withMediator { CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute() }
        return CatalogId(letters, TenantId(tenant.id))
    }

    @Test
    fun `a catalog's default theme is set, read back and cleared`() {
        val catalog = lettersIn("Default Theme")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()

            val set = SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(ThemeKey.of("brand"), letters)).execute()
            assertThat(set.defaultTheme).isEqualTo(CatalogDefaultTheme(ThemeKey.of("brand"), letters))
            assertThat(GetCatalogThemeSettings(catalog.tenantKey, letters).query().defaultTheme?.themeKey).isEqualTo(ThemeKey.of("brand"))

            assertThat(SetCatalogDefaultTheme(catalog.tenantKey, letters, null).execute().defaultTheme).isNull()
        }
    }

    @Test
    fun `a theme of another catalog is declared as a use of it, and a theme that does not exist is refused`() {
        val catalog = lettersIn("Default Theme Elsewhere")
        val shared = CatalogKey.of("shared")
        withMediator {
            CreateCatalog(tenantKey = catalog.tenantKey, id = shared, name = "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(catalog.tenantKey))), "Brand").execute()

            SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(ThemeKey.of("brand"), shared)).execute()
            assertThat(ListCatalogDependencies(catalog.tenantKey, letters).query().map { it.catalogKey }).contains(shared.value)

            assertThatThrownBy { SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(ThemeKey.of("nope"), letters)).execute() }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("does not exist")
        }
    }

    @Test
    fun `a catalog declares its presets, sorted by key, each key once`() {
        val catalog = lettersIn("Presets")
        withMediator {
            val saved = SetCatalogPresets(
                catalog.tenantKey,
                letters,
                listOf(CatalogPreset("small-print", "Small print"), CatalogPreset("heading-accent", "Heading accent", "For section titles")),
            ).execute()
            assertThat(saved.presets.map { it.key }).containsExactly("heading-accent", "small-print")
            assertThat(GetCatalogThemeSettings(catalog.tenantKey, letters).query().presets.first().description).isEqualTo("For section titles")
        }
        assertThatThrownBy { SetCatalogPresets(catalog.tenantKey, letters, listOf(CatalogPreset("a-b", "One"), CatalogPreset("a-b", "Two"))) }
            .isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { CatalogPreset("Not A Key", "Bad") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `a draft renders a template that names no theme with the catalog's default theme`() {
        val catalog = lettersIn("Default Theme Render")
        val template = TemplateId(TemplateKey.of("invoice"), catalog)
        val prepared = withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand", documentStyles = mapOf("color" to "#ff0000")).execute()
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()
            SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(ThemeKey.of("brand"), letters)).execute()

            PrepareDraftRender(
                tenantKey = catalog.tenantKey,
                catalogKey = letters,
                templateId = template.key,
                variantId = VariantKey.INITIAL,
                data = ObjectMapper().createObjectNode(),
                catalogVersion = CatalogVersionRef(catalog.tenantKey, letters, DraftRef.Next),
            ).query()
        }

        assertThat(prepared.theme.documentStyles).containsEntry("color", "#ff0000")
    }
}

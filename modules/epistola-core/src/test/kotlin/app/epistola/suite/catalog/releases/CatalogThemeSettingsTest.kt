// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

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
        assertThatThrownBy { CatalogPreset("a".repeat(51), "Too long") }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the available presets are the catalog's own, then its dependencies', each key once`() {
        val catalog = lettersIn("Available Presets")
        val shared = CatalogKey.of("shared")
        withMediator {
            CreateCatalog(tenantKey = catalog.tenantKey, id = shared, name = "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(catalog.tenantKey))), "Brand").execute()
            SetCatalogPresets(catalog.tenantKey, shared, listOf(CatalogPreset("accent", "Shared accent"), CatalogPreset("small-print", "Small print"))).execute()
            SetCatalogPresets(catalog.tenantKey, letters, listOf(CatalogPreset("accent", "Letters accent"))).execute()
            // Using the shared theme makes letters depend on shared.
            SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(ThemeKey.of("brand"), shared)).execute()

            val available = GetAvailablePresets(catalog.tenantKey, letters).query()

            assertThat(available.map { it.key to it.name }).containsExactly("accent" to "Letters accent", "small-print" to "Small print")
            assertThat(available.last().catalogKey).isEqualTo(shared)
        }
    }

    @Test
    fun `once a catalog declares presets, saving a block that names another one is refused`() {
        val catalog = lettersIn("Preset Use")
        val template = TemplateId(TemplateKey.of("invoice"), catalog)
        val variant = app.epistola.suite.common.ids.VariantId(VariantKey.INITIAL, template)
        fun withPreset(preset: String) = app.epistola.suite.testing.TestTemplateBuilder.buildMinimal(name = "Invoice").let { doc ->
            doc.copy(nodes = doc.nodes.mapValues { (_, node) -> node.copy(stylePreset = preset) })
        }
        withMediator {
            CreateDocumentTemplate(template, "Invoice").execute().withRequiredDataExample()

            // Nothing declared: not checked, as before.
            app.epistola.suite.templates.commands.variants.UpdateVariantContent(variant, withPreset("anything")).execute()

            SetCatalogPresets(catalog.tenantKey, letters, listOf(CatalogPreset("accent", "Accent"))).execute()
            app.epistola.suite.templates.commands.variants.UpdateVariantContent(variant, withPreset("accent")).execute()
            assertThatThrownBy { app.epistola.suite.templates.commands.variants.UpdateVariantContent(variant, withPreset("bogus")).execute() }
                .isInstanceOf(ValidationException::class.java)
                .hasMessageContaining("'bogus' is not one of this catalog's presets")
        }
    }

    @Test
    fun `a published version carries its theme settings through export and import`() {
        val catalog = lettersIn("Theme Settings Export")
        val zip = withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand", blockStylePresets = null).execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice").execute().withRequiredDataExample()
            SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(ThemeKey.of("brand"), letters)).execute()
            SetCatalogPresets(catalog.tenantKey, letters, listOf(CatalogPreset("accent", "Accent", "For titles"))).execute()
            app.epistola.suite.catalog.commands.MarkCatalogReady(catalog.tenantKey, letters).execute()
            app.epistola.suite.catalog.commands.ReleaseCatalogDraft(catalog.tenantKey, letters, "1.0.0").execute()

            assertThat(GetReleasedThemeSettings(catalog.tenantKey, letters, "1.0.0").query().defaultTheme?.themeKey).isEqualTo(ThemeKey.of("brand"))
            app.epistola.suite.catalog.commands.ExportCatalogZip(catalog.tenantKey, letters).execute().zipBytes
        }

        val other = createTenant("Theme Settings Import")
        val imported = withMediator {
            app.epistola.suite.catalog.commands.ImportCatalogZip(other.id, zip).execute()
            GetCatalogThemeSettings(other.id, letters).query()
        }

        assertThat(imported.defaultTheme).isEqualTo(CatalogDefaultTheme(ThemeKey.of("brand"), letters))
        assertThat(imported.presets).containsExactly(CatalogPreset("accent", "Accent", "For titles"))
    }

    @Test
    fun `parallel work starts with the theme settings of the version it copies`() {
        val catalog = lettersIn("Theme Settings Parallel")
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
            SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(ThemeKey.of("brand"), letters)).execute()
            SetCatalogPresets(catalog.tenantKey, letters, listOf(CatalogPreset("accent", "Accent"))).execute()
            app.epistola.suite.catalog.commands.MarkCatalogReady(catalog.tenantKey, letters).execute()
            app.epistola.suite.catalog.commands.ReleaseCatalogDraft(catalog.tenantKey, letters, "1.0.0").execute()

            val fromRelease = requireNotNull(CreateCatalogDraft(catalog.tenantKey, letters, "From release").execute().versionId)
            val fromNext = requireNotNull(CreateCatalogDraft(catalog.tenantKey, letters, "From next", from = "next").execute().versionId)

            for (work in listOf(fromRelease, fromNext)) {
                val settings = GetCatalogThemeSettings(catalog.tenantKey, letters, CatalogReleaseRef(catalog.tenantKey, letters, DraftRef.Id(work))).query()
                assertThat(settings.defaultTheme?.themeKey).`as`(work).isEqualTo(ThemeKey.of("brand"))
                assertThat(settings.presets.map { it.key }).`as`(work).containsExactly("accent")
            }
        }
    }

    @Test
    fun `a theme the version still uses is not deleted`() {
        val catalog = lettersIn("Theme Delete In Use")
        val brand = ThemeId(ThemeKey.of("brand"), catalog)
        val invoice = TemplateId(TemplateKey.of("invoice"), catalog)
        withMediator {
            CreateTheme(brand, "Brand").execute()
            CreateTheme(ThemeId(ThemeKey.of("spare"), catalog), "Spare").execute()
            CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()

            SetCatalogDefaultTheme(catalog.tenantKey, letters, CatalogDefaultTheme(brand.key, letters)).execute()
            assertThatThrownBy { app.epistola.suite.themes.commands.DeleteTheme(brand).execute() }
                .isInstanceOf(app.epistola.suite.themes.ThemeInUseException::class.java)
                .hasMessageContaining("the catalog's default theme")

            SetCatalogDefaultTheme(catalog.tenantKey, letters, null).execute()
            app.epistola.suite.templates.commands.UpdateDocumentTemplate(invoice, themeId = brand.key, themeCatalogKey = letters).execute()
            assertThatThrownBy { app.epistola.suite.themes.commands.DeleteTheme(brand).execute() }
                .isInstanceOf(app.epistola.suite.themes.ThemeInUseException::class.java)
                .hasMessageContaining("template invoice")

            assertThat(app.epistola.suite.themes.commands.DeleteTheme(ThemeId(ThemeKey.of("spare"), catalog)).execute()).`as`("one nothing uses").isTrue
        }
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
                catalogVersion = CatalogReleaseRef(catalog.tenantKey, letters, DraftRef.Next),
            ).query()
        }

        assertThat(prepared.theme.documentStyles).containsEntry("color", "#ff0000")
    }
}

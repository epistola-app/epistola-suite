// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.core.io.ResourceLoader

/**
 * Rename and Move to another catalog are offered on the resource itself (ADR 0042): on every detail
 * page and in every list row, for all seven types, opening the move dialog for that resource in the
 * draft the page shows; never on a release, never with organising catalogs off.
 */
class RefactoringActionsHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private val letters = CatalogKey.of("letters")
    private val image = AssetKey.of("01900000-0000-7000-8000-0000000000aa")

    /** `letters` holding one resource of every type; organising catalogs as [relocation] says. */
    private fun seed(name: String, relocation: Boolean = true): TenantKey = withMediator {
        val tenant = createTenant(name).id
        SaveFeatureToggle(tenant, KnownFeatures.RESOURCE_RELOCATION, enabled = relocation).execute()
        val catalog = testCatalog(TenantId(tenant), letters)
        CreateCatalog(tenant, letters, "Letters").execute()
        CreateTheme(ThemeId(ThemeKey.of("house"), catalog), "House").execute()
        CreateStencil(StencilId(StencilKey.of("header"), catalog), "Header").execute()
        CreateCodeList(CodeListId(CodeListKey.of("langs"), catalog), displayName = "Languages", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("nl", "Nederlands"))).execute()
        CreateAttributeDefinition(AttributeId(AttributeKey.of("tone"), catalog), "Tone", allowedValues = listOf("formal")).execute()
        UploadAsset(catalog = catalog, name = "logo.png", mediaType = AssetMediaType.PNG, content = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), width = 1, height = 1, id = image).execute()
        val ttf = resourceLoader.getResource("classpath:epistola/catalogs/system/binaries/fonts/inter/inter-Regular.ttf").contentAsByteArray
        ImportFont(catalog = catalog, slug = "brandfont", name = "Brand font", kind = FontKind.SANS.wire, variants = listOf(ImportFontVariant(400, false, "font/ttf", ttf))).execute()
        CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice").execute().withRequiredDataExample()
        tenant
    }

    private fun page(path: String): String = restTemplate.getForEntity(path, String::class.java).body!!

    private fun move(tenant: TenantKey, resource: String) = "/tenants/$tenant/catalogs/organise/move?resource=$resource"

    @Test
    fun `every detail page offers Rename and Move to another catalog for its resource`() {
        val tenant = seed("Refactor detail pages")
        val base = "/tenants/$tenant/catalogs/$tenant/letters"
        for ((path, resource) in listOf(
            "$base/themes/house" to "theme:$tenant/letters/house",
            "$base/stencils/header" to "stencil:$tenant/letters/header",
            "$base/code-lists/langs" to "codeList:$tenant/letters/langs",
            "$base/templates/invoice/settings" to "template:$tenant/letters/invoice",
        )) {
            val body = page(path)
            assertThat(body).describedAs(path).contains("data-testid=\"resource-rename\"", "data-testid=\"resource-move\"")
                .contains("${move(tenant, resource)}&amp;draft=&amp;mode=rename", "${move(tenant, resource)}&amp;draft=&amp;mode=move")
                // One mount in the shell opens whichever dialog arrives.
                .contains("id=\"refactoring-mount\"")
        }
    }

    @Test
    fun `every list row offers the dialog for its resource, an image only to move`() {
        val tenant = seed("Refactor list rows")
        val base = "/tenants/$tenant/catalogs/$tenant/letters"
        for ((path, resource) in listOf(
            "$base/themes" to "theme:$tenant/letters/house",
            "$base/stencils" to "stencil:$tenant/letters/header",
            "$base/code-lists" to "codeList:$tenant/letters/langs",
            "$base/attributes" to "attribute:$tenant/letters/tone",
            "$base/fonts" to "font:$tenant/letters/brandfont",
            "$base/images" to "image:$tenant/letters/$image",
            "$base/templates" to "template:$tenant/letters/invoice",
        )) {
            assertThat(page(path)).describedAs(path).contains("data-testid=\"resource-row-refactor\"").contains(move(tenant, resource))
        }
        assertThat(page("$base/images")).contains("title=\"Move to another catalog\"").doesNotContain("Rename or move to another catalog")
    }

    @Test
    fun `a page opened in a draft moves in that draft, and a release offers nothing`() {
        val tenant = seed("Refactor drafts and releases")
        val draft = withMediator {
            MarkCatalogReady(testCatalog(TenantId(tenant), letters)).execute()
            ReleaseCatalogDraft(testCatalog(TenantId(tenant), letters), "1.0.0").execute()
            CreateCatalogDraft(testCatalog(TenantId(tenant), letters), "Redesign", "next").execute().versionId
        }

        assertThat(page("/tenants/$tenant/catalogs/$tenant/letters@$draft/themes/house")).contains("&amp;draft=$draft&amp;mode=move")
        assertThat(page("/tenants/$tenant/catalogs/$tenant/letters@1.0.0/themes/house"))
            .doesNotContain("data-testid=\"resource-move\"", "data-testid=\"resource-rename\"")
    }

    @Test
    fun `nothing is offered with organising catalogs off`() {
        val tenant = seed("Refactor off", relocation = false)
        val base = "/tenants/$tenant/catalogs/$tenant/letters"

        assertThat(page("$base/themes/house")).doesNotContain("data-testid=\"resource-move\"")
        assertThat(page("$base/themes")).doesNotContain("data-testid=\"resource-row-refactor\"")
    }

    @Test
    fun `the dialog is titled for the refactoring chosen and passes it on`() {
        val tenant = seed("Refactor dialog modes")
        fun dialog(mode: String) = restTemplate.exchange(
            "${move(tenant, "theme:$tenant/letters/house")}&mode=$mode",
            org.springframework.http.HttpMethod.GET,
            org.springframework.http.HttpEntity<Void>(org.springframework.http.HttpHeaders().apply { set("HX-Request", "true") }),
            String::class.java,
        ).body!!

        assertThat(dialog("rename")).contains("<h3>Rename</h3>", "data-mode=\"rename\"")
        assertThat(dialog("move")).contains("<h3>Move to another catalog</h3>", "data-mode=\"move\"")
        assertThat(dialog("")).contains("<h3>Rename or move</h3>")
    }
}

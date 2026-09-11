// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.ThemeStyleResolver
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import java.util.UUID

/**
 * Assets and fonts are resolved while rendering, by the address the content names. Relocating one
 * therefore risks something no other movable type does: a published document that renders
 * *successfully* but wrongly — a missing image, or silently falling back to the built-in typeface —
 * with nothing in the move preview to warn about it.
 *
 * These are the tests `MovableResourceGuardTest.aliasAwareRuntimeLookups` points at. Deleting the
 * alias fallback in either query leaves the rest of the suite green and fails only here.
 */
class RuntimeResolutionAfterRelocationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Autowired
    private lateinit var themeStyleResolver: ThemeStyleResolver

    private fun ttfBytes(): ByteArray = resourceLoader
        .getResource("classpath:epistola/fonts/inter/inter-Regular.ttf")
        .contentAsByteArray

    @Test
    fun `a qualified image reference survives its asset moving`() {
        val tenant = createTenant("Asset relocation runtime")
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")

        val assetKey = withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            UploadAsset(
                tenantId = tenant.id,
                name = "logo.png",
                mediaType = AssetMediaType.PNG,
                content = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                width = 1,
                height = 1,
                catalogKey = letters,
            ).execute().id
        }
        val address = ResourceAddress(CatalogResourceType.ASSET, letters.value, assetKey.value)

        // Content written while the asset lived in `letters` qualifies the reference with it.
        assertThat(withMediator { GetAssetContent(tenant.id, assetKey, letters).query() }).isNotNull()

        val relocation = address.movedTo(shared)
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(relocation)).query() }
        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(relocation), preview.planFingerprint).execute() }

        // The published reference still names `letters`, which is now an alias. Without the
        // fallback this returns null and the document renders with a hole where the image was.
        assertThat(withMediator { GetAssetContent(tenant.id, assetKey, letters).query() })
            .describedAs("a qualified reference to the asset's old catalog must follow the alias")
            .isNotNull()
    }

    /**
     * Renaming is the same operation as moving here, and the earlier tests only ever moved. That
     * gap hid a real bug: each fallback took the canonical *catalog* but kept the *requested* key,
     * so a rename resolved to nothing. None of these failures is loud — a theme falls back to the
     * tenant default, a font to the built-in typeface, an image simply disappears — so only an
     * assertion catches them.
     */
    @Test
    fun `a renamed theme is still found by content naming its old key`() {
        val tenant = createTenant("Theme rename runtime")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val themeKey = ThemeKey.of("brand")

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateTheme(ThemeId(themeKey, CatalogId(letters, tenantId)), "Brand").execute()
        }
        val address = ResourceAddress(CatalogResourceType.THEME, letters.value, themeKey.value)

        val renamed = address.renamedTo("house-style")
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(renamed)).query() }
        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(renamed), preview.planFingerprint).execute() }

        assertThat(themeStyleResolver.resolveTheme(tenant.id, themeKey, null, emptyTemplate(), templateCatalogKey = letters))
            .describedAs("a themeRef naming the theme's old key must follow the alias")
            .isNotNull()
    }

    @Test
    fun `an asset cannot be renamed, because its key is what content resolves by`() {
        val tenant = createTenant("Asset rename refused")
        val letters = CatalogKey.of("letters")
        val assetKey = withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            UploadAsset(
                tenantId = tenant.id,
                name = "logo.png",
                mediaType = AssetMediaType.PNG,
                content = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
                width = 1,
                height = 1,
                catalogKey = letters,
            ).execute().id
        }

        // An unqualified image reference carries no catalog, so it resolves by this id alone --
        // there would be nothing left to find the alias with.
        val renamed = ResourceAddress(CatalogResourceType.ASSET, letters.value, assetKey.value)
            .renamedTo(UUID.randomUUID().toString())
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(renamed)).query() }

        assertThat(preview.blockers).anySatisfy { assertThat(it.code).isEqualTo("rename-unsupported") }
        assertThat(preview.executable).isFalse()
    }

    @Test
    fun `a renamed font family still serves the face content asks for`() {
        val tenant = createTenant("Font rename runtime")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val slug = FontKey.of("acme-sans")

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            val face = UploadAsset(
                tenantId = tenant.id,
                name = "acme-sans-regular.ttf",
                mediaType = AssetMediaType.TTF,
                content = ttfBytes(),
                width = null,
                height = null,
                catalogKey = letters,
            ).execute().id
            ImportFont(
                tenantId = tenantId,
                catalogKey = letters,
                slug = slug.value,
                name = "Acme Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = face)),
            ).execute()
        }
        val before = withMediator { ResolveFontFace(tenant.id, letters, slug, 400, italic = false).query() }
        assertThat(before).isNotNull()

        val renamed = ResourceAddress(CatalogResourceType.FONT, letters.value, slug.value).renamedTo("acme-grotesk")
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(renamed)).query() }
        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(renamed), preview.planFingerprint).execute() }

        assertThat(withMediator { ResolveFontFace(tenant.id, letters, slug, 400, italic = false).query() })
            .describedAs("a face referenced by the family's old slug must follow the alias")
            .isEqualTo(before)
    }

    @Test
    fun `a template still finds its theme after the theme moves`() {
        val tenant = createTenant("Theme relocation runtime")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val themeKey = ThemeKey.of("brand")
        val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, tenantId))

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            CreateTheme(ThemeId(themeKey, CatalogId(letters, tenantId)), "Brand").execute()
            CreateDocumentTemplate(templateId, "Invoice").execute()
            UpdateDocumentTemplate(id = templateId, themeId = themeKey, themeCatalogKey = letters).execute()
        }
        val address = ResourceAddress(CatalogResourceType.THEME, letters.value, themeKey.value)

        val relocation = address.movedTo(shared)
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(relocation)).query() }
        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(relocation), preview.planFingerprint).execute() }

        // The template's own binding follows by ON UPDATE CASCADE rather than being rewritten.
        val template = withMediator { GetDocumentTemplate(templateId).query()!! }
        assertThat(template.themeCatalogKey).isEqualTo(shared)
        assertThat(template.themeKey).isEqualTo(themeKey)

        // Content that still names the old catalog resolves through the alias. Without it the
        // template silently falls back to the tenant default rather than failing.
        assertThat(themeStyleResolver.resolveTheme(tenant.id, themeKey, null, emptyTemplate(), templateCatalogKey = letters))
            .describedAs("a themeRef naming the theme's old catalog must follow the alias")
            .isNotNull()
    }

    private fun emptyTemplate(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
    )

    @Test
    fun `a published document keeps its typeface after the font family moves`() {
        val tenant = createTenant("Font relocation runtime")
        val tenantId = TenantId(tenant.id)
        val letters = CatalogKey.of("letters")
        val shared = CatalogKey.of("shared")
        val slug = FontKey.of("acme-sans")

        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateCatalog(tenant.id, shared, "Shared").execute()
            val face = UploadAsset(
                tenantId = tenant.id,
                name = "acme-sans-regular.ttf",
                mediaType = AssetMediaType.TTF,
                content = ttfBytes(),
                width = null,
                height = null,
                catalogKey = letters,
            ).execute().id
            ImportFont(
                tenantId = tenantId,
                catalogKey = letters,
                slug = slug.value,
                name = "Acme Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = face)),
            ).execute()
        }
        val address = ResourceAddress(CatalogResourceType.FONT, letters.value, slug.value)

        val before = withMediator { ResolveFontFace(tenant.id, letters, slug, 400, italic = false).query() }
        assertThat(before).isNotNull()

        val relocation = address.movedTo(shared)
        val preview = withMediator { PreviewCatalogResourceMove(tenant.id, listOf(relocation)).query() }
        assertThat(preview.blockers).isEmpty()
        withMediator { MoveCatalogResources(tenant.id, listOf(relocation), preview.planFingerprint).execute() }

        // A miss here is not an error the caller sees: FontCache falls back to the built-in font,
        // so the document renders in the wrong typeface rather than failing.
        assertThat(withMediator { ResolveFontFace(tenant.id, letters, slug, 400, italic = false).query() })
            .describedAs("a face referenced by the family's old catalog must follow the alias")
            .isEqualTo(before)

        // The faces followed the family, and the backing asset stayed where it was: since
        // V20260905090100 a face names both by identity, so neither move disturbs the other.
        assertThat(withMediator { ResolveFontFace(tenant.id, shared, slug, 400, italic = false).query() }).isEqualTo(before)
    }
}

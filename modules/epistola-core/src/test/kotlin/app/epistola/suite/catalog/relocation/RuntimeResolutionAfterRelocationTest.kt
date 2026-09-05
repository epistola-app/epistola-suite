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
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.ResolveFontFace
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

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
        val address = ResourceAddress(CatalogResourceType.ASSET, letters.value, assetKey.value.toString())

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

        // The faces followed the family, and the backing asset stayed where it was: the two are
        // independent since V20260905090700 split asset_catalog_key out.
        assertThat(withMediator { ResolveFontFace(tenant.id, shared, slug, 400, italic = false).query() }).isEqualTo(before)
    }
}

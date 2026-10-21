// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.merge

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.catalog.protocol.CodeListResource
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.versions.CatalogDefaultTheme
import app.epistola.suite.catalog.versions.GetCatalogThemeSettings
import app.epistola.suite.catalog.versions.SetCatalogDefaultTheme
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * What a merge takes from a release beyond themes (ADR 0032): resources backed by bytes (an image, a
 * font's faces), a code list and the attribute bound to it, and the catalog's settings. A releases
 * 1.0.0, B receives it, changes it and releases 2.0.0, and A, whose next version changed meanwhile,
 * receives 2.0.0 beside its releases and merges it.
 */
class MergeContentKindsTest : IntegrationTestBase() {

    @Autowired private lateinit var contentBuilder: CatalogContentBuilder

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), CatalogId(catalog, TenantId(tenant)))

    /** A and the catalog, with B's 2.0.0 received beside A's releases; [inB] is what B changes, [inA] what A does. */
    private fun received(name: String, inB: (TenantKey, CatalogKey) -> Unit, inA: (TenantKey, CatalogKey) -> Unit = { _, _ -> }): Pair<TenantKey, CatalogKey> {
        val a = createTenant("$name A").id
        val b = createTenant("$name B").id
        val catalog = CatalogKey.of(name.lowercase().replace(' ', '-'))
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = name).execute()
            listOf("tha", "thb").forEach { CreateTheme(id = theme(a, catalog, it), name = "base $it").execute() }
            MarkCatalogReady(a, catalog).execute()
            ReleaseCatalogVersion(tenantKey = a, catalogKey = catalog, version = "1.0.0").execute()
            ImportCatalogZip(tenantKey = b, zipBytes = ExportCatalogZip(a, catalog, "1.0.0").execute().zipBytes).execute()

            inB(b, catalog)
            MarkCatalogReady(b, catalog).execute()
            ReleaseCatalogVersion(tenantKey = b, catalogKey = catalog, version = "2.0.0").execute()
            val v2 = ExportCatalogZip(b, catalog, "2.0.0").execute().zipBytes

            // A change of A's own, so 2.0.0 is received beside its releases rather than moved to.
            UpdateTheme(theme(a, catalog, "tha"), name = "A's a").execute()
            inA(a, catalog)
            ImportCatalogZip(tenantKey = a, zipBytes = v2).execute()
        }
        return a to catalog
    }

    @Test
    fun `an image, a font with its faces, a code list and its attribute are taken with their content`() {
        val (a, catalog) = received("Merge Kinds", inB = { b, catalog ->
            val id = CatalogId(catalog, TenantId(b))
            UploadAsset(b, "Mark", AssetMediaType.PNG, PNG, 1, 1, catalog).execute()
            val face = UploadAsset(b, "Sans Regular", AssetMediaType.fromMimeType("font/ttf"), TTF, width = null, height = null, catalogKey = catalog).execute().id
            ImportFont(TenantId(b), catalog, "house-sans", "House Sans", FontKind.SANS.wire, listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = face))).execute()
            CreateCodeList(CodeListId(CodeListKey.of("regions"), id), "Regions", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("eu", "Europe"))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("region"), id), "Region", codeListId = CodeListId(CodeListKey.of("regions"), id)).execute()
        })
        withMediator {
            val preview = PreviewDraftMerge(a, catalog, "2.0.0").query()
            val added = preview.taken.map { it.contentKey }
            assertThat(added).contains("font/house-sans", "codeList/regions", "attribute/region")
            assertThat(added.filter { it.startsWith("image/") }).`as`("the image, and the font's face").hasSizeGreaterThanOrEqualTo(1)

            val merged = MergeReleaseIntoDraft(a, catalog, "2.0.0", expectedToken = preview.token).execute()

            assertThat(merged.taken).containsExactlyInAnyOrderElementsOf(added)
            val content = contentBuilder.build(a, catalog)
            assertThat(content.resourceDetails.keys).containsAll(added)
            assertThat(content.assetContents.values).`as`("the bytes came along").anyMatch { it.contentEquals(PNG) }.anyMatch { it.contentEquals(TTF) }
            assertThat((content.resourceDetails.getValue("codeList/regions").resource as CodeListResource).entries.map { it.code }).containsExactly("eu")
            assertThat((content.resourceDetails.getValue("attribute/region").resource as AttributeResource).codeListBinding?.key).isEqualTo("regions")
            assertThat(PreviewDraftMerge(a, catalog, "2.0.0").query().resources.filter { it.contentKey in added }.map { it.outcome })
                .`as`("each holds exactly what the release has")
                .containsOnly(MergeOutcome.UNCHANGED)
        }
    }

    @Test
    fun `a default theme only the release changed is taken`() {
        val (a, catalog) = received("Merge Default Theme", inB = { b, catalog ->
            SetCatalogDefaultTheme(b, catalog, CatalogDefaultTheme(ThemeKey.of("thb"), catalog)).execute()
        })
        withMediator {
            val preview = PreviewDraftMerge(a, catalog, "2.0.0").query()
            assertThat(preview.resources.first { it.contentKey == "catalog/defaultTheme" }.let { it.outcome to it.name })
                .isEqualTo(MergeOutcome.THEIRS to "Default theme")

            val merged = MergeReleaseIntoDraft(a, catalog, "2.0.0").execute()

            assertThat(merged.taken).contains("catalog/defaultTheme")
            assertThat(GetCatalogThemeSettings(a, catalog).query().defaultTheme).isEqualTo(CatalogDefaultTheme(ThemeKey.of("thb"), catalog))
            assertThat(contentBuilder.build(a, catalog).resourceDetails.getValue("theme/tha").resource.name).`as`("ours, untouched").isEqualTo("A's a")
        }
    }

    @Test
    fun `a default theme both changed is a conflict, resolved like a resource`() {
        val (a, catalog) = received(
            "Merge Theme Conflict",
            inB = { b, catalog -> SetCatalogDefaultTheme(b, catalog, CatalogDefaultTheme(ThemeKey.of("thb"), catalog)).execute() },
            inA = { a, catalog -> SetCatalogDefaultTheme(a, catalog, CatalogDefaultTheme(ThemeKey.of("tha"), catalog)).execute() },
        )
        withMediator {
            val conflict = PreviewDraftMerge(a, catalog, "2.0.0").query().conflicts.single()
            assertThat(conflict.contentKey to (conflict.ours to conflict.theirs)).isEqualTo("catalog/defaultTheme" to (MergeChange.MODIFIED to MergeChange.MODIFIED))

            MergeReleaseIntoDraft(a, catalog, "2.0.0", resolutions = mapOf("catalog/defaultTheme" to MergeResolution.Ours)).execute()

            assertThat(GetCatalogThemeSettings(a, catalog).query().defaultTheme?.themeKey).isEqualTo(ThemeKey.of("tha"))
        }
    }

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(), 0x89.toByte(),
        )
        val TTF = byteArrayOf(0, 1, 0, 0, 0, 13, 0, -128, 0, 3, 0, 112)
    }
}

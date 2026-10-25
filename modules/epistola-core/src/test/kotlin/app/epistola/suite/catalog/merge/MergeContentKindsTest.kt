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
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
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
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.testCatalog
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

    private fun theme(tenant: TenantKey, catalog: CatalogKey, key: String) = ThemeId(ThemeKey.of(key), testCatalog(TenantId(tenant), catalog))

    /** A and the catalog, with B's 2.0.0 received beside A's releases; [inB] is what B changes, [inA] what A does. */
    private fun received(name: String, inB: (TenantKey, CatalogKey) -> Unit, inA: (TenantKey, CatalogKey) -> Unit = { _, _ -> }): Pair<TenantKey, CatalogKey> {
        val a = createTenant("$name A").id
        val b = createTenant("$name B").id
        val catalog = CatalogKey.of(name.lowercase().replace(' ', '-'))
        withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = name).execute()
            listOf("tha", "thb").forEach { CreateTheme(id = theme(a, catalog, it), name = "base $it").execute() }
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute()
            // The two installations of one organisation hold the catalog's namespace both.
            TestNamespaces.share(a, b).execute()
            ImportCatalogZip(tenantKey = b, zipBytes = ExportCatalogZip(testCatalog(a, catalog), "1.0.0").execute().zipBytes).execute()

            inB(b, catalog)
            MarkCatalogReady(testCatalog(b, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(b, catalog), version = "2.0.0").execute()
            val v2 = ExportCatalogZip(testCatalog(b, catalog), "2.0.0").execute().zipBytes

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
            val id = testCatalog(TenantId(b), catalog)
            UploadAsset(testCatalog(b, catalog), "Mark", AssetMediaType.PNG, PNG, 1, 1).execute()
            ImportFont(testCatalog(TenantId(b), catalog), "house-sans", "House Sans", FontKind.SANS.wire, listOf(ImportFontVariant(400, false, "font/ttf", TTF))).execute()
            CreateCodeList(CodeListId(CodeListKey.of("regions"), id), "Regions", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("eu", "Europe"))).execute()
            CreateAttributeDefinition(AttributeId(AttributeKey.of("region"), id), "Region", codeListId = CodeListId(CodeListKey.of("regions"), id)).execute()
        })
        withMediator {
            val preview = PreviewDraftMerge(testCatalog(a, catalog), "2.0.0").query()
            val added = preview.taken.map { it.contentKey }
            assertThat(added).contains("font/house-sans", "codeList/regions", "attribute/region")
            assertThat(added.filter { it.startsWith("image/") }).`as`("the image, and the font's face").hasSizeGreaterThanOrEqualTo(1)

            val merged = MergeReleaseIntoDraft(testCatalog(a, catalog), "2.0.0", expectedToken = preview.token).execute()

            assertThat(merged.taken).containsExactlyInAnyOrderElementsOf(added)
            val content = contentBuilder.build(testCatalog(a, catalog))
            assertThat(content.resourceDetails.keys).containsAll(added)
            assertThat(content.assetContents.values).`as`("the bytes came along").anyMatch { it.contentEquals(PNG) }.anyMatch { it.contentEquals(TTF) }
            assertThat((content.resourceDetails.getValue("codeList/regions").resource as CodeListResource).entries.map { it.code }).containsExactly("eu")
            assertThat((content.resourceDetails.getValue("attribute/region").resource as AttributeResource).codeListBinding?.key).isEqualTo("regions")
            assertThat(PreviewDraftMerge(testCatalog(a, catalog), "2.0.0").query().resources.filter { it.contentKey in added }.map { it.outcome })
                .`as`("each holds exactly what the release has")
                .containsOnly(MergeOutcome.UNCHANGED)
        }
    }

    @Test
    fun `a default theme only the release changed is taken`() {
        val (a, catalog) = received("Merge Default Theme", inB = { b, catalog ->
            SetCatalogDefaultTheme(testCatalog(b, catalog), CatalogDefaultTheme(ThemeKey.of("thb"), testCatalog(b, catalog))).execute()
        })
        withMediator {
            val preview = PreviewDraftMerge(testCatalog(a, catalog), "2.0.0").query()
            assertThat(preview.resources.first { it.contentKey == "catalog/defaultTheme" }.let { it.outcome to it.name })
                .isEqualTo(MergeOutcome.THEIRS to "Default theme")

            val merged = MergeReleaseIntoDraft(testCatalog(a, catalog), "2.0.0").execute()

            assertThat(merged.taken).contains("catalog/defaultTheme")
            assertThat(GetCatalogThemeSettings(testCatalog(a, catalog)).query().defaultTheme).isEqualTo(CatalogDefaultTheme(ThemeKey.of("thb"), testCatalog(a, catalog)))
            assertThat(contentBuilder.build(testCatalog(a, catalog)).resourceDetails.getValue("theme/tha").resource.name).`as`("ours, untouched").isEqualTo("A's a")
        }
    }

    @Test
    fun `a default theme both changed is a conflict, resolved like a resource`() {
        val (a, catalog) = received(
            "Merge Theme Conflict",
            inB = { b, catalog -> SetCatalogDefaultTheme(testCatalog(b, catalog), CatalogDefaultTheme(ThemeKey.of("thb"), testCatalog(b, catalog))).execute() },
            inA = { a, catalog -> SetCatalogDefaultTheme(testCatalog(a, catalog), CatalogDefaultTheme(ThemeKey.of("tha"), testCatalog(a, catalog))).execute() },
        )
        withMediator {
            val conflict = PreviewDraftMerge(testCatalog(a, catalog), "2.0.0").query().conflicts.single()
            assertThat(conflict.contentKey to (conflict.ours to conflict.theirs)).isEqualTo("catalog/defaultTheme" to (MergeChange.MODIFIED to MergeChange.MODIFIED))

            MergeReleaseIntoDraft(testCatalog(a, catalog), "2.0.0", resolutions = mapOf("catalog/defaultTheme" to MergeResolution.Ours)).execute()

            assertThat(GetCatalogThemeSettings(testCatalog(a, catalog)).query().defaultTheme?.themeKey).isEqualTo(ThemeKey.of("tha"))
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

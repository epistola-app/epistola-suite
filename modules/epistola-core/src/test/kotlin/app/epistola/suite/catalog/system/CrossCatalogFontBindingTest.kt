// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.system

import app.epistola.catalog.protocol.CatalogManifest
import app.epistola.catalog.protocol.DependencyRef
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
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
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import tools.jackson.databind.ObjectMapper

/**
 * Cross-catalog font binding: a theme authored in catalog A references a
 * font family that lives in the bundled `system` catalog (every tenant gets
 * `system/inter`). This is the font analogue of
 * [CrossCatalogCodeListBindingTest].
 *
 * Covers:
 *  - export emits a `DependencyRef.Font(catalogKey = "system", slug = "inter")`
 *    on the manifest for the cross-catalog ref,
 *  - re-import into a *different* fresh tenant installs cleanly (the system
 *    font exists there too) and the theme's font ref resolves to real bytes,
 *  - a theme referencing a *same-catalog* AUTHORED font does NOT emit a
 *    cross-catalog `DependencyRef.Font` (it is an own `font` resource — which
 *    still appears in the manifest's `resources`, just not in `dependencies`).
 *
 * The manifest is deserialized into [CatalogManifest] and `dependencies` is
 * asserted structurally: a substring match on `"type" : "font"` is ambiguous
 * because an own font resource also serializes that string under `resources`.
 */
class CrossCatalogFontBindingTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun ttfBytes(): ByteArray = resourceLoader
        .getResource("classpath:epistola/fonts/inter/inter-Regular.ttf")
        .contentAsByteArray

    private fun manifestOf(zipBytes: ByteArray): CatalogManifest = objectMapper.readValue(readZipEntry(zipBytes, "catalog.json"), CatalogManifest::class.java)

    @Test
    fun `export emits DependencyRef Font for a system font ref, re-import into a fresh tenant resolves it`() {
        val tenant = createTenant("CrossFont1")
        val source = CatalogKey.of("xfont-source")

        val zip = withMediator {
            CreateCatalog(tenantKey = tenant.id, id = source, name = "Xfont Source").execute()
            CreateTheme(
                id = ThemeId(ThemeKey.of("branded"), CatalogId(source, TenantId(tenant.id))),
                name = "Branded",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("slug" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value),
                ),
            ).execute()

            ExportCatalogZip(tenantKey = tenant.id, catalogKey = source).execute()
        }

        // The cross-catalog font binding is declared on the manifest as a
        // DependencyRef.Font(catalogKey = "system", slug = "inter").
        val deps = manifestOf(zip.zipBytes).dependencies.orEmpty()
        assertThat(deps).contains(DependencyRef.Font(catalogKey = SYSTEM_CATALOG_KEY.value, slug = "inter"))

        // Re-import into a SECOND fresh tenant. createTenant auto-installs the
        // system catalog (incl. system/inter), so the cross-catalog font
        // dependency is satisfied and the import is clean.
        val target = createTenant("CrossFont1-Target")

        withMediator {
            val result = CatalogImportContext.runAsImport {
                ImportCatalogZip(
                    tenantKey = target.id,
                    zipBytes = zip.zipBytes,
                    catalogType = CatalogType.AUTHORED,
                ).execute()
            }
            assertThat(result.results).allSatisfy { r ->
                assertThat(r.status).isNotEqualTo(InstallStatus.FAILED)
            }

            // The referenced system font resolves to real embeddable bytes on
            // the target tenant.
            val bytes = ResolveFontFace(
                tenantId = target.id,
                catalogKey = SYSTEM_CATALOG_KEY,
                slug = FontKey.of("inter"),
                weight = 400,
                italic = false,
            ).query()
            assertThat(bytes).isNotNull()
            assertThat(bytes!!).isNotEmpty()
        }
    }

    @Test
    fun `a theme referencing a same-catalog AUTHORED font emits no cross-catalog DependencyRef Font`() {
        val tenant = createTenant("CrossFont2")
        val tenantId = TenantId(tenant.id)
        val authored = CatalogKey.of("own-font-catalog")

        val zip = withMediator {
            CreateCatalog(tenantKey = tenant.id, id = authored, name = "Own Font Catalog").execute()

            // Upload + register an AUTHORED font living in THIS catalog.
            val regular = UploadAsset(
                tenantId = tenant.id,
                name = "acme-sans-regular.ttf",
                mediaType = AssetMediaType.TTF,
                content = ttfBytes(),
                width = null,
                height = null,
                catalogKey = authored,
            ).execute().id
            ImportFont(
                tenantId = tenantId,
                catalogKey = authored,
                slug = "acme-sans",
                name = "Acme Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = regular)),
            ).execute()

            // A theme in the SAME catalog referencing that own font.
            CreateTheme(
                id = ThemeId(ThemeKey.of("own-theme"), CatalogId(authored, tenantId)),
                name = "Own Theme",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("slug" to "acme-sans", "catalogKey" to authored.value),
                ),
            ).execute()

            ExportCatalogZip(tenantKey = tenant.id, catalogKey = authored).execute()
        }

        val manifest = manifestOf(zip.zipBytes)
        // The font IS an own resource of this catalog...
        assertThat(manifest.resources.map { it.type to it.slug }).contains("font" to "acme-sans")
        // ...but it is NOT a cross-catalog dependency.
        assertThat(manifest.dependencies.orEmpty().filterIsInstance<DependencyRef.Font>()).isEmpty()
    }

    private fun readZipEntry(content: ByteArray, entryName: String): String {
        java.util.zip.ZipInputStream(content.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == entryName) return zis.readBytes().toString(Charsets.UTF_8)
                entry = zis.nextEntry
            }
        }
        error("$entryName not found in zip")
    }
}

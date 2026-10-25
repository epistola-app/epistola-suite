// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.fonts

import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontId
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.fonts.commands.DeleteFont
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontInUseException
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.queries.FindFontUsages
import app.epistola.suite.fonts.queries.GetFontVariantContent
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

/**
 * Mediator-level coverage for the customer font lifecycle: register a family
 * with its faces' files via [ImportFont], guard SUBSCRIBED catalogs,
 * and delete the family with its faces ([DeleteFont]) with the
 * [FindFontUsages] in-use guard.
 *
 * Mirrors `app.epistola.suite.assets.AssetIntegrationTest` (asset machinery
 * reuse) and `SystemFontsIntegrationTest` (real TTF byte fixture).
 *
 * Test bodies use a block body wrapping `withMediator` (not an `=` expression
 * body) so the JUnit `@Test` method returns `void`. A non-void Kotlin test
 * method is silently skipped by the JUnit 5 platform.
 */
class FontUploadAndDeletionTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    /** A real bundled TTF used as the uploaded face binary. */
    private fun ttfBytes(): ByteArray = resourceLoader
        .getResource("classpath:epistola/fonts/inter/inter-Regular.ttf")
        .contentAsByteArray

    /** A face's file: since ADR 0033 step 5 the font's own binary, not an uploaded asset. */
    @Suppress("UNUSED_PARAMETER")
    private fun uploadFace(tenant: TenantId, catalog: CatalogKey, name: String): ByteArray = ttfBytes()

    @Test
    fun `register a font family from uploaded faces into an AUTHORED catalog`() {
        withMediator {
            val tenant = createTenant("Font Upload Tenant")
            val tenantId = TenantId(tenant.id)

            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "acme-sans-regular.ttf")
            val bold = uploadFace(tenantId, CatalogKey.DEFAULT, "acme-sans-bold.ttf")

            ImportFont(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                slug = "acme-sans",
                name = "Acme Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(
                    ImportFontVariant(400, false, "font/ttf", regular),
                    ImportFontVariant(700, false, "font/ttf", bold),
                ),
            ).execute()

            val fonts = ListFonts(tenantId = tenantId, catalog = testCatalog(tenantId, CatalogKey.DEFAULT)).query()
            val acme = fonts.single { it.slug.value == "acme-sans" }
            assertThat(acme.name).isEqualTo("Acme Sans")
            assertThat(acme.kind).isEqualTo(FontKind.SANS)

            val variants = GetFontVariants(FontId(FontKey.of("acme-sans"), testCatalog(tenantId, CatalogKey.DEFAULT)))
                .query().map { it.weight to it.italic }
            assertThat(variants).containsExactlyInAnyOrder(400 to false, 700 to false)
        }
    }

    @Test
    fun `register a font into the system SUBSCRIBED catalog is rejected`() {
        withMediator {
            createTenant("Font System Reject Tenant")
            // A face's file is the font's own; the font itself targets system → CatalogReadOnlyException.
            assertThatThrownBy {
                ImportFont(
                    catalog = testCatalog(createTenant("Font System Reject Tenant 2").id, SYSTEM_CATALOG_KEY),
                    slug = "rogue",
                    name = "Rogue",
                    kind = FontKind.SANS.wire,
                    variants = listOf(ImportFontVariant(400, false, "font/ttf", ttfBytes())),
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `delete font removes the family and its faces`() {
        withMediator {
            val tenant = createTenant("Font Delete Tenant")
            val tenantId = TenantId(tenant.id)

            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "del-regular.ttf")
            ImportFont(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                slug = "delete-me",
                name = "Delete Me",
                kind = FontKind.SERIF.wire,
                variants = listOf(ImportFontVariant(400, false, "font/ttf", regular)),
            ).execute()

            val deleted = DeleteFont(FontId(FontKey.of("delete-me"), testCatalog(tenantId, CatalogKey.DEFAULT))).execute()
            assertThat(deleted).isTrue()

            assertThat(ListFonts(tenantId = tenantId, catalog = testCatalog(tenantId, CatalogKey.DEFAULT)).query().map { it.slug.value })
                .doesNotContain("delete-me")
            // The backing ASSET binary is gone too.
            // Its face's file goes with it: nothing of the draft names it any more.
            assertThat(GetFontVariantContent(testCatalog(tenantId, CatalogKey.DEFAULT), FontKey.of("delete-me"), 400, false).query()).isNull()
        }
    }

    @Test
    fun `delete font in the system catalog is rejected`() {
        withMediator {
            val tenant = createTenant("Font Delete System Tenant")
            val tenantId = TenantId(tenant.id)

            assertThatThrownBy {
                DeleteFont(FontId(FontKey.of("inter"), testCatalog(tenantId, SYSTEM_CATALOG_KEY))).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `delete font is blocked when referenced by a theme`() {
        withMediator {
            val tenant = createTenant("Font In-Use Tenant")
            val tenantId = TenantId(tenant.id)

            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "used-regular.ttf")
            ImportFont(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                slug = "used-font",
                name = "Used Font",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, "font/ttf", regular)),
            ).execute()

            CreateTheme(
                id = ThemeId(ThemeKey.of("brand-theme"), testCatalog(tenantId, CatalogKey.DEFAULT)),
                name = "Brand Theme",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("key" to "used-font", "catalogKey" to "default"),
                ),
            ).execute()

            val fontId = FontId(FontKey.of("used-font"), testCatalog(tenantId, CatalogKey.DEFAULT))

            val usages = FindFontUsages(fontId).query()
            assertThat(usages).hasSize(1)
            assertThat(usages[0].kind).isEqualTo("theme")
            assertThat(usages[0].name).isEqualTo("Brand Theme")

            assertThatThrownBy { DeleteFont(fontId).execute() }
                .isInstanceOf(FontInUseException::class.java)
                .hasMessageContaining("Brand Theme")

            // force = true overrides the guard.
            assertThat(DeleteFont(fontId, force = true).execute()).isTrue()
        }
    }

    @Test
    fun `a theme stored before 2_0, naming its font by slug, still counts as using it`() {
        withMediator {
            val tenant = createTenant("Font Slug Tenant")
            val tenantId = TenantId(tenant.id)
            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "slug-regular.ttf")
            ImportFont(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                slug = "slug-font",
                name = "Slug Font",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, "font/ttf", regular)),
            ).execute()
            // The wire-v7 member name, as 1.x stored it.
            CreateTheme(
                id = ThemeId(ThemeKey.of("old-theme"), testCatalog(tenantId, CatalogKey.DEFAULT)),
                name = "Old Theme",
                documentStyles = mapOf("fontFamily" to mapOf("slug" to "slug-font", "catalogKey" to "default")),
            ).execute()

            val usages = FindFontUsages(FontId(FontKey.of("slug-font"), testCatalog(tenantId, CatalogKey.DEFAULT))).query()

            assertThat(usages.map { it.name }).containsExactly("Old Theme")
        }
    }

    @Test
    fun `find font usages returns empty for an unreferenced font`() {
        withMediator {
            val tenant = createTenant("Font Unused Tenant")
            val tenantId = TenantId(tenant.id)

            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "lonely-regular.ttf")
            ImportFont(
                catalog = testCatalog(tenantId, CatalogKey.DEFAULT),
                slug = "lonely-font",
                name = "Lonely Font",
                kind = FontKind.MONO.wire,
                variants = listOf(ImportFontVariant(400, false, "font/ttf", regular)),
            ).execute()

            val usages = FindFontUsages(
                FontId(FontKey.of("lonely-font"), testCatalog(tenantId, CatalogKey.DEFAULT)),
            ).query()
            assertThat(usages).isEmpty()
        }
    }

    @Test
    fun `re-import replaces variants supporting catalog round-trip into another tenant`() {
        withMediator {
            val source = createTenant("Font Export Tenant")
            val sourceId = TenantId(source.id)
            val regular = uploadFace(sourceId, CatalogKey.DEFAULT, "rt-regular.ttf")
            ImportFont(
                catalog = testCatalog(sourceId, CatalogKey.DEFAULT),
                slug = "round-trip",
                name = "Round Trip",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, "font/ttf", regular)),
            ).execute()

            // Simulate catalog import into a second tenant: re-import the same
            // family + a freshly-uploaded asset binary (ImportFont upserts).
            val target = createTenant("Font Import Tenant")
            val targetId = TenantId(target.id)
            val importedAsset = uploadFace(targetId, CatalogKey.DEFAULT, "rt-imported.ttf")
            ImportFont(
                catalog = testCatalog(targetId, CatalogKey.DEFAULT),
                slug = "round-trip",
                name = "Round Trip",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, "font/ttf", importedAsset)),
            ).execute()

            val imported = ListFonts(tenantId = targetId, catalog = testCatalog(targetId, CatalogKey.DEFAULT)).query()
                .single { it.slug.value == "round-trip" }
            assertThat(imported.name).isEqualTo("Round Trip")
            assertThat(
                GetFontVariants(FontId(FontKey.of("round-trip"), testCatalog(targetId, CatalogKey.DEFAULT)))
                    .query().map { it.weight to it.italic },
            ).containsExactly(400 to false)
        }
    }
}

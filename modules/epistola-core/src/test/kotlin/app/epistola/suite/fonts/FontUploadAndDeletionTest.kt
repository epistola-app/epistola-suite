package app.epistola.suite.fonts

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.assets.queries.GetAsset
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.common.ids.AssetKey
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
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.FindFontUsages
import app.epistola.suite.fonts.queries.GetFontVariants
import app.epistola.suite.fonts.queries.ListFonts
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

/**
 * Mediator-level coverage for the customer font lifecycle: register a family
 * from uploaded asset binaries via [ImportFont], guard SUBSCRIBED catalogs,
 * and delete the family + its backing assets ([DeleteFont]) with the
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

    private fun uploadFace(tenant: TenantId, catalog: CatalogKey, name: String): AssetKey = UploadAsset(
        tenantId = tenant.key,
        name = name,
        mediaType = AssetMediaType.TTF,
        content = ttfBytes(),
        width = null,
        height = null,
        catalogKey = catalog,
    ).execute().id

    @Test
    fun `register a font family from uploaded faces into an AUTHORED catalog`() {
        withMediator {
            val tenant = createTenant("Font Upload Tenant")
            val tenantId = TenantId(tenant.id)

            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "acme-sans-regular.ttf")
            val bold = uploadFace(tenantId, CatalogKey.DEFAULT, "acme-sans-bold.ttf")

            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "acme-sans",
                name = "Acme Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(
                    ImportFontVariant("regular", FontVariantSource.ASSET, assetKey = regular),
                    ImportFontVariant("bold", FontVariantSource.ASSET, assetKey = bold),
                ),
            ).execute()

            val fonts = ListFonts(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query()
            val acme = fonts.single { it.slug.value == "acme-sans" }
            assertThat(acme.name).isEqualTo("Acme Sans")
            assertThat(acme.kind).isEqualTo(FontKind.SANS)

            val variants = GetFontVariants(FontId(FontKey.of("acme-sans"), CatalogId(CatalogKey.DEFAULT, tenantId)))
                .query().map { it.variant.wire }
            assertThat(variants).containsExactlyInAnyOrder("regular", "bold")
        }
    }

    @Test
    fun `register a font into the system SUBSCRIBED catalog is rejected`() {
        withMediator {
            createTenant("Font System Reject Tenant")
            // The asset upload itself targets system → CatalogReadOnlyException.
            assertThatThrownBy {
                UploadAsset(
                    tenantId = createTenant("Font System Reject Tenant 2").id,
                    name = "rogue.ttf",
                    mediaType = AssetMediaType.TTF,
                    content = ttfBytes(),
                    width = null,
                    height = null,
                    catalogKey = SYSTEM_CATALOG_KEY,
                ).execute()
            }.isInstanceOf(CatalogReadOnlyException::class.java)
        }
    }

    @Test
    fun `delete font removes the family and its backing assets`() {
        withMediator {
            val tenant = createTenant("Font Delete Tenant")
            val tenantId = TenantId(tenant.id)

            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "del-regular.ttf")
            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "delete-me",
                name = "Delete Me",
                kind = FontKind.SERIF.wire,
                variants = listOf(ImportFontVariant("regular", FontVariantSource.ASSET, assetKey = regular)),
            ).execute()

            val deleted = DeleteFont(FontId(FontKey.of("delete-me"), CatalogId(CatalogKey.DEFAULT, tenantId))).execute()
            assertThat(deleted).isTrue()

            assertThat(ListFonts(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query().map { it.slug.value })
                .doesNotContain("delete-me")
            // The backing ASSET binary is gone too.
            assertThat(GetAsset(tenantId = tenant.id, assetId = regular).query()).isNull()
        }
    }

    @Test
    fun `delete font in the system catalog is rejected`() {
        withMediator {
            val tenant = createTenant("Font Delete System Tenant")
            val tenantId = TenantId(tenant.id)

            assertThatThrownBy {
                DeleteFont(FontId(FontKey.of("inter"), CatalogId(SYSTEM_CATALOG_KEY, tenantId))).execute()
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
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "used-font",
                name = "Used Font",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant("regular", FontVariantSource.ASSET, assetKey = regular)),
            ).execute()

            CreateTheme(
                id = ThemeId(ThemeKey.of("brand-theme"), CatalogId(CatalogKey.DEFAULT, tenantId)),
                name = "Brand Theme",
                documentStyles = mapOf(
                    "fontFamily" to mapOf("slug" to "used-font", "catalogKey" to "default"),
                ),
            ).execute()

            val fontId = FontId(FontKey.of("used-font"), CatalogId(CatalogKey.DEFAULT, tenantId))

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
    fun `find font usages returns empty for an unreferenced font`() {
        withMediator {
            val tenant = createTenant("Font Unused Tenant")
            val tenantId = TenantId(tenant.id)

            val regular = uploadFace(tenantId, CatalogKey.DEFAULT, "lonely-regular.ttf")
            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "lonely-font",
                name = "Lonely Font",
                kind = FontKind.MONO.wire,
                variants = listOf(ImportFontVariant("regular", FontVariantSource.ASSET, assetKey = regular)),
            ).execute()

            val usages = FindFontUsages(
                FontId(FontKey.of("lonely-font"), CatalogId(CatalogKey.DEFAULT, tenantId)),
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
                tenantId = sourceId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "round-trip",
                name = "Round Trip",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant("regular", FontVariantSource.ASSET, assetKey = regular)),
            ).execute()

            // Simulate catalog import into a second tenant: re-import the same
            // family + a freshly-uploaded asset binary (ImportFont upserts).
            val target = createTenant("Font Import Tenant")
            val targetId = TenantId(target.id)
            val importedAsset = uploadFace(targetId, CatalogKey.DEFAULT, "rt-imported.ttf")
            ImportFont(
                tenantId = targetId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "round-trip",
                name = "Round Trip",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant("regular", FontVariantSource.ASSET, assetKey = importedAsset)),
            ).execute()

            val imported = ListFonts(tenantId = targetId, catalogKey = CatalogKey.DEFAULT).query()
                .single { it.slug.value == "round-trip" }
            assertThat(imported.name).isEqualTo("Round Trip")
            assertThat(
                GetFontVariants(FontId(FontKey.of("round-trip"), CatalogId(CatalogKey.DEFAULT, targetId)))
                    .query().map { it.variant.wire },
            ).containsExactly("regular")
        }
    }
}

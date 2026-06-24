package app.epistola.suite.fonts

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.fonts.queries.ListFontsWithVariants
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader

/**
 * [ListFontsWithVariants] is the batch query that replaced the per-font [GetFontVariants]
 * N+1 on the fonts list. It must return each family with exactly its own faces (the @Nested
 * batch-row mapping + the (catalog, slug) grouping), and honour the search filter.
 */
class ListFontsWithVariantsTest : IntegrationTestBase() {

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private fun bytesOf(resource: String): ByteArray = resourceLoader.getResource("classpath:$resource").contentAsByteArray

    private fun upload(tenant: TenantId, name: String, resource: String): AssetKey = UploadAsset(
        tenantId = tenant.key,
        name = name,
        mediaType = AssetMediaType.TTF,
        content = bytesOf(resource),
        width = null,
        height = null,
        catalogKey = CatalogKey.DEFAULT,
    ).execute().id

    private fun importFont(tenant: TenantId, slug: String, name: String, vararg faces: Pair<Int, AssetKey>) {
        ImportFont(
            tenantId = tenant,
            catalogKey = CatalogKey.DEFAULT,
            slug = slug,
            name = name,
            kind = FontKind.SANS.wire,
            variants = faces.map { (weight, key) -> ImportFontVariant(weight, false, FontVariantSource.ASSET, assetKey = key) },
        ).execute()
    }

    @Test
    fun `returns each font grouped with its own faces`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Fonts With Variants").id)
        importFont(
            tenantId,
            "inter",
            "Inter",
            400 to upload(tenantId, "inter-reg.ttf", "epistola/fonts/inter/inter-Regular.ttf"),
            700 to upload(tenantId, "inter-bold.ttf", "epistola/fonts/inter/inter-Bold.ttf"),
        )
        importFont(tenantId, "mono", "Mono", 400 to upload(tenantId, "mono-reg.ttf", "epistola/fonts/inter/inter-Regular.ttf"))

        // Scope to the tenant's own catalog: an unscoped query also returns the seeded `system` fonts.
        val fonts = ListFontsWithVariants(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT).query()

        assertThat(fonts.map { it.font.slug.value }).containsExactlyInAnyOrder("inter", "mono")
        assertThat(fonts.single { it.font.slug.value == "inter" }.variants.map { it.weight })
            .containsExactlyInAnyOrder(400, 700)
        assertThat(fonts.single { it.font.slug.value == "mono" }.variants.map { it.weight }).containsExactly(400)
    }

    @Test
    fun `search filters by family name`(): Unit = withMediator {
        val tenantId = TenantId(createTenant("Fonts Search").id)
        importFont(tenantId, "inter", "Inter", 400 to upload(tenantId, "a.ttf", "epistola/fonts/inter/inter-Regular.ttf"))
        importFont(tenantId, "roboto", "Roboto", 400 to upload(tenantId, "b.ttf", "epistola/fonts/inter/inter-Regular.ttf"))

        val fonts = ListFontsWithVariants(tenantId = tenantId, catalogKey = CatalogKey.DEFAULT, searchTerm = "rob").query()

        assertThat(fonts.map { it.font.name }).containsExactly("Roboto")
    }
}

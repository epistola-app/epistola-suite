package app.epistola.suite.mcp

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mcp.tools.FontMcpTools
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import java.util.UUID

/**
 * Read-only font discovery via MCP. Exercises `list_fonts` against the bundled
 * `system` catalog every tenant gets, plus a tenant-uploaded AUTHORED family.
 * Mirrors `CodeListAndAttributeMcpToolsIntegrationTest`.
 */
class FontMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var fontMcpTools: FontMcpTools

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    private fun <T> runAsApiKey(tenantId: TenantId, block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        val principal = EpistolaPrincipal(
            userId = UserKey.of(UUID.randomUUID()),
            externalId = "apikey-test",
            email = "test@apikey.example",
            displayName = "Test API Key",
            tenantMemberships = mapOf(tenantId.key to TenantRole.entries.toSet()),
            globalRoles = emptySet(),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = tenantId.key,
        )
        SecurityContext.runWithPrincipal(principal, block)
    }

    @Test
    fun `list_fonts returns the eight bundled system families read-only`() {
        val tenant = createTenant("MCP Font Tenant")
        val tenantId = TenantId(tenant.id)

        val systemFonts = runAsApiKey(tenantId) { fontMcpTools.listFonts(catalogId = "system") }
        assertThat(systemFonts).hasSize(8)
        assertThat(systemFonts.map { it.slug }).contains("inter", "roboto", "jetbrains-mono")
        assertThat(systemFonts).allMatch { it.readOnly && it.catalogType == "SUBSCRIBED" }
        val inter = systemFonts.single { it.slug == "inter" }
        assertThat(inter.name).isEqualTo("Inter")
        assertThat(inter.kind).isEqualTo("sans")
        assertThat(inter.variants.map { it.weight to it.italic })
            .containsExactlyInAnyOrder(400 to false, 700 to false, 400 to true, 700 to true)
    }

    @Test
    fun `list_fonts surfaces an uploaded AUTHORED family`() {
        val tenant = createTenant("MCP Font Upload Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val asset = UploadAsset(
                tenantId = tenant.id,
                name = "acme-regular.ttf",
                mediaType = AssetMediaType.TTF,
                content = resourceLoader.getResource("classpath:epistola/fonts/inter/inter-Regular.ttf").contentAsByteArray,
                width = null,
                height = null,
                catalogKey = CatalogKey.DEFAULT,
            ).execute()
            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.DEFAULT,
                slug = "acme-sans",
                name = "Acme Sans",
                kind = FontKind.SANS.wire,
                variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = asset.id)),
            ).execute()
        }

        val all = runAsApiKey(tenantId) { fontMcpTools.listFonts(catalogId = null) }
        val acme = all.single { it.slug == "acme-sans" }
        assertThat(acme.catalog).isEqualTo("default")
        assertThat(acme.catalogType).isEqualTo("AUTHORED")
        assertThat(acme.readOnly).isFalse
        assertThat(acme.variants.map { it.weight to it.italic }).containsExactly(400 to false)
        // System families still present in the unfiltered listing.
        assertThat(all.map { it.slug }).contains("inter")
    }

    @Test
    fun `no font write tool is exposed`() {
        val mcpToolMethods = FontMcpTools::class.java.methods
            .filter { it.isAnnotationPresent(org.springframework.ai.mcp.annotation.McpTool::class.java) }
        assertThat(mcpToolMethods).hasSize(1)
        assertThat(mcpToolMethods[0].name).isEqualTo("listFonts")
    }
}

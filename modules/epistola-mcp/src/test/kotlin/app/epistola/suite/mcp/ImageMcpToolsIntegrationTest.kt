// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.ImageMcpTools
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
import java.util.UUID

class ImageMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var imageMcpTools: ImageMcpTools

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
    fun `list_images returns only images and get_image resolves exact metadata`() {
        val tenant = createTenant("MCP Image Tenant")
        val tenantId = TenantId(tenant.id)
        val image = withMediator {
            val uploaded = UploadAsset(
                tenantId = tenant.id,
                name = "letterhead-logo.png",
                mediaType = AssetMediaType.PNG,
                content = byteArrayOf(1, 2, 3),
                width = 320,
                height = 80,
                catalogKey = CatalogKey.DEFAULT,
            ).execute()
            UploadAsset(
                tenantId = tenant.id,
                name = "letterhead-font.ttf",
                mediaType = AssetMediaType.TTF,
                content = byteArrayOf(4, 5, 6),
                width = null,
                height = null,
                catalogKey = CatalogKey.DEFAULT,
            ).execute()
            uploaded
        }

        val listed = runAsApiKey(tenantId) {
            imageMcpTools.listImages(catalogId = "default", search = "letterhead")
        }
        assertThat(listed).hasSize(1)
        assertThat(listed.single().id).isEqualTo(image.id.value.toString())
        assertThat(listed.single().name).isEqualTo("letterhead-logo.png")

        val resolved = runAsApiKey(tenantId) {
            imageMcpTools.getImage(catalogId = "default", imageId = image.id.value.toString())
        }
        assertThat(resolved).isNotNull
        assertThat(resolved!!.mediaType).isEqualTo("image/png")
        assertThat(resolved.width).isEqualTo(320)
        assertThat(resolved.height).isEqualTo(80)

        val wrongCatalog = runAsApiKey(tenantId) {
            imageMcpTools.getImage(catalogId = "system", imageId = image.id.value.toString())
        }
        assertThat(wrongCatalog).isNull()
    }

    @Test
    fun `image MCP surface exposes no write tools`() {
        val mcpToolMethods = ImageMcpTools::class.java.methods
            .filter { it.isAnnotationPresent(org.springframework.ai.mcp.annotation.McpTool::class.java) }
        assertThat(mcpToolMethods.map { it.name }).containsExactlyInAnyOrder("listImages", "getImage")
    }
}

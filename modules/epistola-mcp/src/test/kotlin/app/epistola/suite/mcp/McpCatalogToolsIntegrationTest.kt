// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.CatalogMcpTools
import app.epistola.suite.mcp.tools.ImageMcpTools
import app.epistola.suite.mcp.tools.StencilMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mcp.tools.ThemeMcpTools
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Catalog handling over MCP: an assistant starting in a fresh tenant can create the catalog it
 * builds in, and a list filtered by a catalog that does not exist says so instead of looking empty.
 */
class McpCatalogToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var catalogTools: CatalogMcpTools

    @Autowired private lateinit var templateTools: TemplateMcpTools

    @Autowired private lateinit var themeTools: ThemeMcpTools

    @Autowired private lateinit var stencilTools: StencilMcpTools

    @Autowired private lateinit var imageTools: ImageMcpTools

    /** Like the API-key filter: a tenant-scoped service principal whose `users` row exists. */
    private fun <T> runAsApiKey(
        tenantId: TenantId,
        roles: Set<TenantRole> = TenantRole.entries.toSet(),
        block: () -> T,
    ): T = UUID.randomUUID().let { keyId ->
        runAs(
            EpistolaPrincipal(
                userId = UserKey.of(keyId),
                externalId = "apikey:$keyId",
                email = "apikey-$keyId@npa.epistola",
                displayName = "Test API Key",
                tenantMemberships = mapOf(tenantId.key to roles),
                globalRoles = emptySet(),
                platformRoles = emptySet(),
                currentTenantId = tenantId.key,
            ),
            block,
        )
    }

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Catalogs ${UUID.randomUUID()}").id)

    @Test
    fun `create_catalog creates an authored catalog that the write tools can build in`() {
        val tenantId = newTenant()

        val catalog = runAsApiKey(tenantId) { catalogTools.createCatalog("sociaal-domein", "Sociaal domein", "Brieven") }

        assertThat(catalog.id).isEqualTo("sociaal-domein")
        assertThat(catalog.type).isEqualTo("AUTHORED")
        assertThat(catalog.description).isEqualTo("Brieven")
        assertThat(runAsApiKey(tenantId) { catalogTools.listCatalogs() }.map { it.id }).contains("sociaal-domein")

        runAsApiKey(tenantId) { templateTools.createTemplate("sociaal-domein", "uitnodiging", "Uitnodiging", null, null, null) }
        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates("sociaal-domein", null) }.map { it.id })
            .containsExactly("uitnodiging")
    }

    @Test
    fun `create_catalog refuses a key that is taken`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { catalogTools.createCatalog("brieven", "Brieven", null) }

        assertThatThrownBy { runAsApiKey(tenantId) { catalogTools.createCatalog("brieven", "Other", null) } }
            .hasMessageContaining("brieven")
    }

    @Test
    fun `create_catalog needs the CATALOG_MANAGE permission`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId, roles = setOf(TenantRole.CONTENT_AUTHOR, TenantRole.CONTENT_PUBLISHER)) {
                catalogTools.createCatalog("brieven", "Brieven", null)
            }
        }.hasMessageContaining("CATALOG_MANAGE")
        assertThat(runAsApiKey(tenantId) { catalogTools.listCatalogs() }.map { it.id }).doesNotContain("brieven")
    }

    @Test
    fun `list tools reject a catalog that does not exist instead of returning an empty list`() {
        val tenantId = newTenant()
        val listings = mapOf<String, () -> List<Any>>(
            "list_templates" to { templateTools.listTemplates("sociaaldomein", null) },
            "list_themes" to { themeTools.listThemes("sociaaldomein", null) },
            "list_stencils" to { stencilTools.listStencils("sociaaldomein", null, null) },
            "list_images" to { imageTools.listImages("sociaaldomein", null) },
        )

        listings.forEach { (tool, call) ->
            assertThatThrownBy { runAsApiKey(tenantId) { call() } }
                .describedAs(tool)
                .isInstanceOf(NoSuchElementException::class.java)
                .hasMessageContaining("Catalog 'sociaaldomein' not found")
                .hasMessageContaining("list_catalogs")
        }
    }

    @Test
    fun `list tools still return an empty list for an existing empty catalog, and all catalogs when none is given`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { catalogTools.createCatalog("leeg", "Leeg", null) }

        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates("leeg", null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { themeTools.listThemes("leeg", null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { stencilTools.listStencils("leeg", null, null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { imageTools.listImages("leeg", null) }).isEmpty()
        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates(null, null) }).isNotNull
        assertThat(runAsApiKey(tenantId) { templateTools.listTemplates("", null) }).isNotNull
    }
}

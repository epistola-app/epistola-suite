// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.StencilMcpTools
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * The MCP stencil read tools expose a stencil's one content and parameter schema (`get_stencil`),
 * and which templates still fit it (`list_stencil_usages`).
 */
class StencilMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var stencilTools: StencilMcpTools

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun parameterSchema(): ObjectNode = objectMapper.readTree(
        """{"type":"object","properties":{"recipientName":{"type":"string"}},"required":["recipientName"]}""",
    ) as ObjectNode

    private fun <T> runAsApiKey(tenantId: TenantId, block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        val tenantPrincipal = EpistolaPrincipal(
            userId = UserKey.of(UUID.randomUUID()),
            externalId = "apikey-test",
            email = "test@apikey.example",
            displayName = "Test API Key",
            tenantMemberships = mapOf(tenantId.key to TenantRole.entries.toSet()),
            globalRoles = emptySet(),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            currentTenantId = tenantId.key,
        )
        SecurityContext.runWithPrincipal(tenantPrincipal, block)
    }

    @Test
    fun `get_stencil returns the content and the full parameter schema`() {
        val tenantId = TenantId(createTenant("MCP Get Stencil Tenant").id)
        val stencilKey = withMediator {
            val id = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
            CreateStencil(id = id, name = "Parametrised Header", parameterSchema = parameterSchema()).execute()
            id.key
        }

        val stencil = runAsApiKey(tenantId) { stencilTools.getStencil(catalogId = "default", stencilId = stencilKey.value) }!!

        assertThat(stencil.stencil.parameterSchema!!.get("properties").has("recipientName")).isTrue
        assertThat(stencil.stencil.readsTemplateData).isFalse()
        assertThat(stencil.content.nodes).containsKey(stencil.content.root)
    }

    @Test
    fun `get_stencil returns null for a stencil that does not exist`() {
        val tenantId = TenantId(createTenant("MCP Missing Stencil Tenant").id)

        assertThat(runAsApiKey(tenantId) { stencilTools.getStencil(catalogId = "default", stencilId = "nonexistent") }).isNull()
    }

    @Test
    fun `list_stencil_usages refuses a stencil that does not exist`() {
        val tenantId = TenantId(createTenant("MCP Missing Stencil Usage Tenant").id)

        assertThatThrownBy { runAsApiKey(tenantId) { stencilTools.listStencilUsages(catalogId = "default", stencilId = "nonexistent", page = null) } }
            .hasMessageContaining("nonexistent")
    }
}

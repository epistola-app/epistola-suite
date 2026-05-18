package app.epistola.suite.mcp

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mcp.tools.StencilMcpTools
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Verifies that the MCP stencil version tools (`list_stencil_versions`,
 * `get_stencil_version`) correctly expose parameter schemas and content.
 */
class StencilVersionMcpToolsIntegrationTest : IntegrationTestBase() {

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
    fun `list_stencil_versions includes parameter schema`() {
        val tenant = createTenant("MCP Stencil Versions Tenant")
        val tenantId = TenantId(tenant.id)

        val stencilKey = withMediator {
            val id = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
            CreateStencil(
                id = id,
                name = "Parametrised Letter",
                parameterSchema = parameterSchema(),
            ).execute()
            id.key
        }

        val versions = runAsApiKey(tenantId) {
            stencilTools.listStencilVersions(
                catalogId = "default",
                stencilId = stencilKey.value,
            )
        }

        assertThat(versions).hasSize(1)
        val v1 = versions[0]
        assertThat(v1.version).isEqualTo(1)
        assertThat(v1.status).isEqualTo("draft")
        assertThat(v1.parameterSchema).isNotNull
        assertThat(v1.parameterSchema!!.get("type").asText()).isEqualTo("object")
        assertThat(v1.parameterSchema.get("properties").has("recipientName")).isTrue
    }

    @Test
    fun `get_stencil_version returns full schema and content`() {
        val tenant = createTenant("MCP Get Stencil Version Tenant")
        val tenantId = TenantId(tenant.id)

        val stencilKey = withMediator {
            val id = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
            CreateStencil(
                id = id,
                name = "Published Parametrised Header",
                parameterSchema = parameterSchema(),
            ).execute()
            PublishStencilVersion(
                versionId = StencilVersionId(VersionKey.of(1), id),
            ).execute()
            id.key
        }

        val version = runAsApiKey(tenantId) {
            stencilTools.getStencilVersion(
                catalogId = "default",
                stencilId = stencilKey.value,
                version = 1,
            )
        }

        assertThat(version).isNotNull
        assertThat(version!!.version).isEqualTo(1)
        assertThat(version.status).isEqualTo("published")
        assertThat(version.parameterSchema).isNotNull
        assertThat(version.parameterSchema!!.get("type").asText()).isEqualTo("object")
        assertThat(version.content).isNotNull
        assertThat(version.content!!.nodes).isNotEmpty
    }

    @Test
    fun `list_stencil_versions returns empty for nonexistent stencil`() {
        val tenant = createTenant("MCP Empty Stencil Versions Tenant")
        val tenantId = TenantId(tenant.id)

        val versions = runAsApiKey(tenantId) {
            stencilTools.listStencilVersions(
                catalogId = "default",
                stencilId = "nonexistent",
            )
        }

        assertThat(versions).isEmpty()
    }

    @Test
    fun `get_stencil_version returns null for nonexistent version`() {
        val tenant = createTenant("MCP Null Stencil Version Tenant")
        val tenantId = TenantId(tenant.id)

        val stencilKey = withMediator {
            val id = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))
            CreateStencil(id = id, name = "No Version 2").execute()
            id.key
        }

        val version = runAsApiKey(tenantId) {
            stencilTools.getStencilVersion(
                catalogId = "default",
                stencilId = stencilKey.value,
                version = 99,
            )
        }

        assertThat(version).isNull()
    }
}

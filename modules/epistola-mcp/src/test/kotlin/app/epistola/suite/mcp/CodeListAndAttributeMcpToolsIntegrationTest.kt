package app.epistola.suite.mcp

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.AttributeMcpTools
import app.epistola.suite.mcp.tools.CodeListMcpTools
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Read-only discovery for code lists + attributes via MCP. Exercises the
 * tool surface against the bundled `system` catalog every tenant gets at
 * creation time — the canonical real-world fixture.
 */
class CodeListAndAttributeMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired
    private lateinit var codeListMcpTools: CodeListMcpTools

    @Autowired
    private lateinit var attributeMcpTools: AttributeMcpTools

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
    fun `list_code_lists returns the system catalog reserved lists`() {
        val tenant = createTenant("MCP CL Tenant")
        val tenantId = TenantId(tenant.id)

        val all = runAsApiKey(tenantId) { codeListMcpTools.listCodeLists(catalogId = null) }
        // System catalog ships these three.
        val slugs = all.map { it.slug }
        assertThat(slugs).contains("bcp-47", "iso-639-1", "iso-3166-1-alpha2")
        // All system entries are SUBSCRIBED/readOnly.
        val systemLists = all.filter { it.catalog == "system" }
        assertThat(systemLists).isNotEmpty
        assertThat(systemLists).allMatch { it.readOnly && it.catalogType == "SUBSCRIBED" }
    }

    @Test
    fun `list_code_lists filtered by catalog returns only that catalog`() {
        val tenant = createTenant("MCP CL Filter Tenant")
        val tenantId = TenantId(tenant.id)

        val systemOnly = runAsApiKey(tenantId) { codeListMcpTools.listCodeLists(catalogId = "system") }
        assertThat(systemOnly).isNotEmpty
        assertThat(systemOnly).allMatch { it.catalog == "system" }
    }

    @Test
    fun `get_code_list returns one bound list`() {
        val tenant = createTenant("MCP CL Get Tenant")
        val tenantId = TenantId(tenant.id)

        val bcp47 = runAsApiKey(tenantId) { codeListMcpTools.getCodeList("system", "bcp-47") }
        assertThat(bcp47).isNotNull
        assertThat(bcp47!!.slug).isEqualTo("bcp-47")
        assertThat(bcp47.catalog).isEqualTo("system")
        assertThat(bcp47.readOnly).isTrue
        // Bundled-catalog imports land as INLINE rows in the tenant DB.
        assertThat(bcp47.sourceType).isEqualTo("INLINE")
    }

    @Test
    fun `list_code_list_entries returns the bound entries`() {
        val tenant = createTenant("MCP CL Entries Tenant")
        val tenantId = TenantId(tenant.id)

        val entries = runAsApiKey(tenantId) {
            codeListMcpTools.listCodeListEntries("system", "iso-639-1", includeHidden = false)
        }
        val codes = entries.map { it.code }
        // Spot-check a few well-known ISO 639-1 codes.
        assertThat(codes).contains("en", "nl", "fr", "de")
    }

    @Test
    fun `list_attributes returns the system reserved attributes`() {
        val tenant = createTenant("MCP Attr List Tenant")
        val tenantId = TenantId(tenant.id)

        val all = runAsApiKey(tenantId) { attributeMcpTools.listAttributes(catalogId = "system") }
        val keys = all.map { it.key }
        assertThat(keys).contains("locale", "language", "country")
        assertThat(all).allMatch { it.readOnly && it.catalogType == "SUBSCRIBED" }
    }

    @Test
    fun `get_attribute returns binding for system locale`() {
        val tenant = createTenant("MCP Attr Get Tenant")
        val tenantId = TenantId(tenant.id)

        val locale = runAsApiKey(tenantId) { attributeMcpTools.getAttribute("system", "locale") }
        assertThat(locale).isNotNull
        assertThat(locale!!.codeListBinding).isNotNull
        assertThat(locale.codeListBinding!!.catalog).isEqualTo("system")
        assertThat(locale.codeListBinding!!.slug).isEqualTo("bcp-47")
        assertThat(locale.readOnly).isTrue
    }

    @Test
    fun `get_attribute returns null for missing attribute`() {
        val tenant = createTenant("MCP Attr 404 Tenant")
        val tenantId = TenantId(tenant.id)

        val missing = runAsApiKey(tenantId) { attributeMcpTools.getAttribute("system", "no-such-attr") }
        assertThat(missing).isNull()
    }
}

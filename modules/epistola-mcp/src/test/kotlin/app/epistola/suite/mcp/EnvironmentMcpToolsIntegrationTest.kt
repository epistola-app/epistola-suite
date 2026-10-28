// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.mcp.tools.EnvironmentMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** `list_environments`: the environments an assistant can name in `preview_document`, and what each serves. */
class EnvironmentMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var tools: EnvironmentMcpTools

    private fun <T> asMember(tenant: TenantKey, roles: Set<TenantRole>, block: () -> T): T = UUID.randomUUID().let { id ->
        runAs(
            EpistolaPrincipal(
                userId = UserKey.of(id),
                externalId = "apikey:$id",
                email = "apikey-$id@npa.epistola",
                displayName = "Test API Key",
                tenantMemberships = mapOf(tenant to roles),
                globalRoles = emptySet(),
                platformRoles = emptySet(),
                currentTenantId = tenant,
            ),
            block,
        )
    }

    @Test
    fun `lists each environment with the releases deployed to it`() {
        val tenant = createTenant("MCP Environments").id
        val catalog = CatalogKey.of("mcp-envs")
        val staging = EnvironmentId(EnvironmentKey.of("staging"), TenantId(tenant))
        withMediator {
            CreateCatalog(tenantKey = tenant, id = catalog, name = "MCP environments").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), catalog)), name = "Brand").execute()
            MarkCatalogReady(testCatalog(tenant, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.0.0").execute()
            CreateEnvironment(staging, "Staging").execute()
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant)), "Production", production = true).execute()
            DeployCatalogRelease(staging, testCatalog(tenant, catalog), "1.0.0").execute()
        }
        val namespace = withMediator { GetCatalog(testCatalog(tenant, catalog)).query()!!.namespace }

        val listed = asMember(tenant, setOf(TenantRole.CONTENT_VIEWER)) { tools.listEnvironments() }.environments.associateBy { it.id }

        assertThat(listed.keys).containsExactlyInAnyOrder("staging", "production")
        assertThat(listed.getValue("production").production).isTrue()
        assertThat(listed.getValue("production").deployments).isEmpty()
        val deployed = listed.getValue("staging").deployments.single()
        assertThat(deployed.catalogId).isEqualTo("$namespace/mcp-envs")
        assertThat(deployed.version).isEqualTo("1.0.0")
        assertThat(deployed.draftName).isNull()
    }

    @Test
    fun `needs TEMPLATE_VIEW`() {
        val tenant = createTenant("MCP Environments Perm").id

        assertThatThrownBy { asMember(tenant, emptySet()) { tools.listEnvironments() } }
            .hasMessageContaining("TEMPLATE_VIEW")
    }
}

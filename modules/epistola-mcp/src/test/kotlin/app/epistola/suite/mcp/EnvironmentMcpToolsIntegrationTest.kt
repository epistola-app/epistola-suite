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
import app.epistola.suite.documents.EnvironmentNotFoundException
import app.epistola.suite.documents.NoReleaseDeployedException
import app.epistola.suite.environments.ReleaseNotDeployableException
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
import app.epistola.suite.themes.commands.UpdateTheme
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

    /** A tenant with catalog `mcp-deploy` released as 1.0.0 and 1.1.0, and environment `staging`; its catalog address. */
    private fun deployable(name: String): Pair<TenantKey, String> {
        val tenant = createTenant(name).id
        val catalog = CatalogKey.of("mcp-deploy")
        withMediator {
            CreateCatalog(tenantKey = tenant, id = catalog, name = "MCP deploy").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), catalog)), name = "Brand").execute()
            MarkCatalogReady(testCatalog(tenant, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.0.0").execute()
            UpdateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant), catalog)), name = "Brand 2").execute()
            MarkCatalogReady(testCatalog(tenant, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, catalog), version = "1.1.0").execute()
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("staging"), TenantId(tenant)), "Staging").execute()
        }
        return tenant to "${withMediator { GetCatalog(testCatalog(tenant, catalog)).query()!!.namespace }}/mcp-deploy"
    }

    private val publisher = setOf(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_PUBLISHER)

    @Test
    fun `a deployed release shows in list_environments, and undeploying takes it away`() {
        val (tenant, catalog) = deployable("MCP Deploy")

        val deployed = asMember(tenant, publisher) { tools.deployRelease("staging", catalog, "1.0.0") }
        assertThat(deployed.deployments.map { it.catalogId to it.version }).containsExactly(catalog to "1.0.0")

        val beside = asMember(tenant, publisher) { tools.deployRelease("staging", catalog, "1.1.0") }
        assertThat(beside.deployments.map { it.version }).`as`("served beside, highest first").containsExactly("1.1.0", "1.0.0")
        val listed = asMember(tenant, publisher) { tools.listEnvironments() }.environments.single { it.id == "staging" }
        assertThat(listed).isEqualTo(beside)

        val replaced = asMember(tenant, publisher) { tools.deployRelease("staging", catalog, "1.0.0", replace = true) }
        assertThat(replaced.deployments.map { it.version }).containsExactly("1.0.0")

        val undeployed = asMember(tenant, publisher) { tools.undeployRelease("staging", catalog, "1.0.0") }
        assertThat(undeployed.deployments).isEmpty()
        assertThatThrownBy { asMember(tenant, publisher) { tools.undeployRelease("staging", catalog, "1.0.0") } }
            .`as`("nothing left to undeploy")
            .isInstanceOf(NoReleaseDeployedException::class.java)
    }

    @Test
    fun `deploying and undeploying need ENVIRONMENT_DEPLOY`() {
        val (tenant, catalog) = deployable("MCP Deploy Perm")
        val viewerAndAuthor = setOf(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)

        assertThatThrownBy { asMember(tenant, viewerAndAuthor) { tools.deployRelease("staging", catalog, "1.0.0") } }
            .hasMessageContaining("ENVIRONMENT_DEPLOY")
        asMember(tenant, publisher) { tools.deployRelease("staging", catalog, "1.0.0") }
        assertThatThrownBy { asMember(tenant, viewerAndAuthor) { tools.undeployRelease("staging", catalog, "1.0.0") } }
            .hasMessageContaining("ENVIRONMENT_DEPLOY")
    }

    @Test
    fun `an unknown release or environment is refused by name`() {
        val (tenant, catalog) = deployable("MCP Deploy Unknown")

        assertThatThrownBy { asMember(tenant, publisher) { tools.deployRelease("staging", catalog, "9.9.9") } }
            .isInstanceOf(ReleaseNotDeployableException::class.java)
            .hasMessageContaining("9.9.9")
        assertThatThrownBy { asMember(tenant, publisher) { tools.deployRelease("nowhere", catalog, "1.0.0") } }
            .isInstanceOf(EnvironmentNotFoundException::class.java)
            .hasMessageContaining("nowhere")
        assertThatThrownBy { asMember(tenant, publisher) { tools.undeployRelease("nowhere", catalog, "1.0.0") } }
            .isInstanceOf(EnvironmentNotFoundException::class.java)
    }
}

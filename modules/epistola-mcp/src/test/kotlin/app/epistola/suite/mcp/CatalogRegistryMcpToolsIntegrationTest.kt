// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogReleaseSummaries
import app.epistola.suite.catalog.registry.ReleaseRegistryNotFoundException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.CatalogRegistryMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.SharedRegistries
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/** The registry route over MCP (alpha): publish from one tenant, list and install in another. */
class CatalogRegistryMcpToolsIntegrationTest : IntegrationTestBase() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registries(registry: DynamicPropertyRegistry) = SharedRegistries.register(registry)
    }

    @Autowired private lateinit var tools: CatalogRegistryMcpTools

    private fun <T> asMember(tenant: TenantKey, roles: Set<TenantRole> = TenantRole.entries.toSet(), block: () -> T): T = UUID.randomUUID().let { id ->
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
    fun `a release is published, listed and installed in another tenant over MCP`() {
        val a = createTenant("MCP Registry A").id
        val b = createTenant("MCP Registry B").id
        val catalog = CatalogKey.of("mcp-registry")
        val fingerprint = withMediator {
            CreateCatalog(tenantKey = a, id = catalog, name = "MCP registry").execute()
            CreateTheme(id = ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(a), catalog)), name = "Brand").execute()
            MarkCatalogReady(testCatalog(a, catalog)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(a, catalog), version = "1.0.0").execute().fingerprint
        }
        val namespace = withMediator { GetCatalog(testCatalog(a, catalog)).query()!!.namespace }

        val published = asMember(a) { tools.publishReleaseToRegistry("mcp-registry", "1.0.0", "local") }
        assertThat(published.status).isEqualTo("published")
        assertThat(published.reference).endsWith("/$namespace/mcp-registry:1.0.0")
        assertThat(asMember(a) { tools.publishReleaseToRegistry("mcp-registry", "1.0.0", "local") }.status).isEqualTo("already-published")
        assertThat(asMember(a) { tools.listReleaseRegistries() }.registries.map { it.name to it.kind }).containsExactly("local" to "container", "secured" to "container")

        val listed = asMember(b) { tools.listRegistryReleases("local", namespace, "mcp-registry") }
        assertThat(listed.releases.map { it.version to it.present }).containsExactly("1.0.0" to false)

        val installed = asMember(b) { tools.installReleaseFromRegistry("local", namespace, "mcp-registry", "1.0.0") }
        assertThat(installed.newlyInstalled).isTrue()
        assertThat(withMediator { ListCatalogReleaseSummaries(testCatalog(b, catalog)).query().single().fingerprint }).isEqualTo(fingerprint)
    }

    @Test
    fun `publishing needs CATALOG_PUBLISH and a registry name when several are configured`() {
        val a = createTenant("MCP Registry Perm").id
        withMediator {
            CreateCatalog(tenantKey = a, id = CatalogKey.of("mcp-registry-perm"), name = "Perm").execute()
            MarkCatalogReady(testCatalog(a, CatalogKey.of("mcp-registry-perm"))).execute()
        }

        assertThatThrownBy { asMember(a, setOf(TenantRole.CONTENT_VIEWER)) { tools.publishReleaseToRegistry("mcp-registry-perm", "1.0.0", "local") } }
            .hasMessageContaining("CATALOG_PUBLISH")
        assertThatThrownBy { asMember(a) { tools.publishReleaseToRegistry("mcp-registry-perm", "1.0.0", null) } }
            .isInstanceOf(ReleaseRegistryNotFoundException::class.java)
            .hasMessageContaining("There are: local, secured")
    }
}

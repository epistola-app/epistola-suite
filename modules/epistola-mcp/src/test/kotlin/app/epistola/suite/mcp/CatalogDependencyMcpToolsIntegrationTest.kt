// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.dto.CatalogDependencyInput
import app.epistola.suite.mcp.tools.CatalogMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** The MCP tools that declare, list and resolve the catalogs a catalog depends on. */
class CatalogDependencyMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var catalogTools: CatalogMcpTools

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

    /** A tenant with `letters` and `shared`, shared released as 1.0.0 and 1.1.0. */
    private fun setUp(): TenantId {
        val tenantId = TenantId(createTenant("MCP deps ${UUID.randomUUID()}").id)
        runAsApiKey(tenantId) {
            catalogTools.createCatalog("letters", "Letters", null)
            catalogTools.createCatalog("shared", "Shared", null)
        }
        withMediator {
            ReleaseCatalogVersion(tenantId.key, CatalogKey.of("shared"), "1.0.0").execute()
            ReleaseCatalogVersion(tenantId.key, CatalogKey.of("shared"), "1.1.0").execute()
        }
        return tenantId
    }

    @Test
    fun `a dependency is declared by catalog key and locked to the highest release its range matches`() {
        val tenantId = setUp()

        val declared = runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared", "~1.0.0"))) }

        assertThat(declared.single().lockedRelease).isEqualTo("1.0.0")
        assertThat(declared.single().namespace).isEqualTo(tenantId.key.value)
        assertThat(runAsApiKey(tenantId) { catalogTools.listCatalogDependencies("letters") }).isEqualTo(declared)

        runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared", "^1.0.0"))) }
        assertThat(runAsApiKey(tenantId) { catalogTools.resolveCatalogDependencies("letters") }.single().lockedRelease).isEqualTo("1.1.0")
    }

    @Test
    fun `declaring needs TEMPLATE_PUBLISH and a catalog the tenant has`() {
        val tenantId = setUp()

        assertThatThrownBy {
            runAsApiKey(tenantId, setOf(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)) {
                catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared")))
            }
        }.hasMessageContaining("TEMPLATE_PUBLISH")
        assertThatThrownBy {
            runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("nowhere"))) }
        }.hasMessageContaining("Catalog not found: nowhere")
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.releases.CatalogReleaseStatus
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.dto.CatalogDependencyInput
import app.epistola.suite.mcp.tools.CatalogMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** The MCP tools that declare, list and resolve the catalogs a catalog depends on. */
class CatalogDependencyMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var catalogTools: CatalogMcpTools

    @Autowired private lateinit var objectMapper: ObjectMapper

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
            ReleaseCatalogDraft(testCatalog(tenantId.key, CatalogKey.of("shared")), "1.0.0").execute()
            ReleaseCatalogDraft(testCatalog(tenantId.key, CatalogKey.of("shared")), "1.1.0").execute()
        }
        return tenantId
    }

    @Test
    fun `a dependency is declared by catalog key and pins the release named, or the highest`() {
        val tenantId = setUp()

        val declared = runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared", release = "1.0.0"))) }

        assertThat(declared.single().release).isEqualTo("1.0.0")
        assertThat(declared.single().namespace).isEqualTo(tenantId.key.value)
        assertThat(runAsApiKey(tenantId) { catalogTools.listCatalogDependencies("letters") }).isEqualTo(declared)

        assertThat(runAsApiKey(tenantId) { catalogTools.upgradeCatalogDependencies("letters") }.single().release).isEqualTo("1.1.0")
        assertThat(runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared"))) }.single().release)
            .`as`("none named: the highest").isEqualTo("1.1.0")
    }

    /**
     * A declaration says the alias the catalog's content names the dependency by (contract ADR 0003,
     * ADR 0034): its key, in this installation. Asserted on the tool's result as JSON, as MCP sends it.
     */
    @Test
    fun `a dependency is listed with the alias the content names it by`() {
        val tenantId = setUp()
        runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared", release = "1.0.0"))) }

        val listed = objectMapper.valueToTree<JsonNode>(runAsApiKey(tenantId) { catalogTools.listCatalogDependencies("letters") })

        assertThat(listed.size()).isEqualTo(1)
        assertThat(listed[0].path("catalogId").asString()).isEqualTo("shared")
        assertThat(listed[0].path("alias").asString(null)).isEqualTo("shared")
    }

    /**
     * A declaration and a catalog name the content hash of the release they mean (ADR 0034), as REST's
     * `CatalogDependencyDto` and `CatalogDto` do. Asserted on the tools' results as JSON.
     */
    @Test
    fun `a dependency names the pinned release's content hash, and a catalog its highest release's`() {
        val tenantId = setUp()
        val (v100, v110) = withMediator {
            val releases = ListCatalogReleases(testCatalog(tenantId.key, CatalogKey.of("shared")), CatalogReleaseStatus.RELEASED).query().associate { it.version to it.contentHash }
            releases["1.0.0"]!! to releases["1.1.0"]!!
        }

        val declared = objectMapper.valueToTree<JsonNode>(runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared", release = "1.0.0"))) })
        val listed = objectMapper.valueToTree<JsonNode>(runAsApiKey(tenantId) { catalogTools.listCatalogDependencies("letters") })
        val catalogs = objectMapper.valueToTree<JsonNode>(runAsApiKey(tenantId) { catalogTools.listCatalogs() })

        assertThat(declared[0].path("contentHash").asString(null)).isEqualTo(v100).startsWith("v6:")
        assertThat(listed[0].path("contentHash").asString(null)).isEqualTo(v100)
        assertThat(catalogs.single { it.path("id").asString() == "shared" }.path("contentHash").asString(null)).isEqualTo(v110)
    }

    @Test
    fun `declaring needs TEMPLATE_EDIT and a catalog the tenant has`() {
        val tenantId = setUp()

        assertThatThrownBy {
            runAsApiKey(tenantId, setOf(TenantRole.CONTENT_VIEWER)) {
                catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("shared")))
            }
        }.hasMessageContaining("TEMPLATE_EDIT")
        assertThatThrownBy {
            runAsApiKey(tenantId) { catalogTools.updateCatalogDependencies("letters", listOf(CatalogDependencyInput("nowhere"))) }
        }.hasMessageContaining("Catalog 'nowhere' not found")
    }
}

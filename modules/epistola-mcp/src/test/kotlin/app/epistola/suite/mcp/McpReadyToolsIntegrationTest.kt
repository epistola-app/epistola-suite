// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.dto.CatalogChangesInfo
import app.epistola.suite.mcp.tools.DataContractMcpTools
import app.epistola.suite.mcp.tools.ReadyMcpTools
import app.epistola.suite.mcp.tools.StencilMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mcp.tools.VariantContentMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The MCP ready tools: marking a catalog built over MCP ready for release — template variants,
 * templates, stencils and whole catalogs — with the same checks the UI and REST API apply.
 */
class McpReadyToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var readyTools: ReadyMcpTools

    @Autowired private lateinit var templateTools: TemplateMcpTools

    @Autowired private lateinit var contentTools: VariantContentMcpTools

    @Autowired private lateinit var stencilTools: StencilMcpTools

    @Autowired private lateinit var contractTools: DataContractMcpTools

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

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Ready ${UUID.randomUUID()}").id)

    /** A stencil `letterhead` with a `body` placeholder, and a template `letter` using it (sent inlined, as the editor does). */
    private fun seedTemplateOnStencil(tenantId: TenantId, fill: String): String = runAsApiKey(tenantId) {
        stencilTools.createStencil("default", "letterhead", "Letterhead", null, null, LETTERHEAD, null, null)
        templateTools.createTemplate("default", "letter", "Letter", null, null, null)
        contractTools.updateDataContract("default", "letter", NAME_SCHEMA, """[{"name": "Jane", "data": {"name": "Jane"}}]""")
        val variant = templateTools.listVariants("default", "letter").single().id
        contentTools.updateVariantContent("default", "letter", variant, templateWithStencil(fill), null)
        variant
    }

    @Test
    fun `a template variant using a stencil is marked ready, storing the instance by reference`() {
        val tenantId = newTenant()
        val variant = seedTemplateOnStencil(tenantId, fill = "body")

        val changes = runAsApiKey(tenantId) { readyTools.markVariantReady("default", "letter", variant) }

        assertThat(changes.resources.single { it.type == "template" && it.key == "letter" }.ready).isTrue
        val instance = (runAsApiKey(tenantId) { templateTools.getTemplateContent("default", "letter", variant) }!!.templateModel as TemplateDocument)
            .nodes.getValue("stencil-1").props!!
        assertThat(instance).containsEntry("stencilKey", "letterhead").doesNotContainKeys("version", "draftVersion")
    }

    @Test
    fun `marking ready is refused while an instance fills a placeholder its stencil lacks, and the usage lists it`() {
        val tenantId = newTenant()
        val variant = seedTemplateOnStencil(tenantId, fill = "footer")

        assertThatThrownBy {
            runAsApiKey(tenantId) { readyTools.markVariantReady("default", "letter", variant) }
        }.hasMessageContaining("fills placeholder 'footer', which stencil default/letterhead does not have")

        val usage = runAsApiKey(tenantId) { stencilTools.listStencilUsages("default", "letterhead", null) }.single()
        assertThat(usage.templateId).isEqualTo("letter")
        assertThat(usage.fits).isFalse()
        assertThat(usage.problems.single()).contains("'footer'")
    }

    @Test
    fun `mark_catalog_ready marks every changed resource, after which the catalog is releasable`() {
        val tenantId = newTenant()
        seedTemplateOnStencil(tenantId, fill = "body")

        val before = runAsApiKey(tenantId) { readyTools.getCatalogChanges("default") }
        assertThat(before.releasable).isFalse
        assertThat(before.resources.map { it.type to it.key }).contains("template" to "letter", "stencil" to "letterhead")

        val after = runAsApiKey(tenantId) { readyTools.markCatalogReady("default") }
        assertThat(after.releasable).isTrue
        assertThat(after.resources.filter { it.state == "NEW" || it.state == "MODIFIED" }).isNotEmpty.allSatisfy {
            assertThat(it.ready).isTrue
        }
    }

    /** A dependency is content of the release (ADR 0034): listed under `settings`, without a ready mark to wait on. */
    @Test
    fun `get_catalog_changes lists a dependency added to a published catalog under settings`() {
        val tenantId = newTenant()
        seedTemplateOnStencil(tenantId, fill = "body")
        val system = runAsApiKey(tenantId) {
            readyTools.markCatalogReady("default")
            ReleaseCatalogDraft(testCatalog(tenantId.key, CatalogKey.of("default")), "1.0.0").execute()
            val system = GetCatalog(testCatalog(tenantId.key, CatalogKey.of("system"))).query()!!
            UpdateCatalogDependencies(testCatalog(tenantId.key, CatalogKey.of("default")), listOf(DeclaredCatalogDependency(system.registry, system.namespace, "system", system.latestReleaseVersion))).execute()
            system
        }

        val changes = runAsApiKey(tenantId) { readyTools.getCatalogChanges("default") }

        assertThat(changes.resources.map { it.state }).containsOnly("RELEASED")
        assertThat(changes.settings).containsExactly(
            CatalogChangesInfo.Setting("catalogDependency/system", "NEW", system.name, releaseVersion = system.latestReleaseVersion),
        )
        assertThat(changes.releasable).isTrue
    }

    @Test
    fun `marking ready needs the edit permission`() {
        val tenantId = newTenant()
        val variant = seedTemplateOnStencil(tenantId, fill = "body")

        assertThatThrownBy {
            runAsApiKey(tenantId, setOf(TenantRole.CONTENT_VIEWER)) { readyTools.markVariantReady("default", "letter", variant) }
        }.hasMessageContaining("TEMPLATE_EDIT")
    }

    @Test
    fun `ready tools refuse a SUBSCRIBED catalog`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId) { readyTools.markCatalogReady("system") }
        }.hasMessageContaining("Epistola supplies this catalog")
    }

    /**
     * A template whose root holds one `letterhead` instance as the editor sends it: the stencil's
     * content inlined, with the template's own text in the fill of placeholder [fill].
     */
    private fun templateWithStencil(fill: String): String = """
        {"modelVersion": 1, "root": "root",
         "nodes": {
           "root": {"id": "root", "type": "root", "slots": ["slot-root"]},
           "stencil-1": {"id": "stencil-1", "type": "stencil", "slots": ["slot-stencil"],
                         "props": {"stencilKey": "letterhead", "catalogKey": "default"}},
           ${textNode("copy-1", "ACME")},
           "ph": {"id": "ph", "type": "placeholder", "slots": ["ph-fill"], "props": {"name": "$fill"}},
           ${textNode("mine", "Dear Jane")}
         },
         "slots": {
           "slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": ["stencil-1"]},
           "slot-stencil": {"id": "slot-stencil", "nodeId": "stencil-1", "name": "children", "children": ["copy-1", "ph"]},
           "ph-fill": {"id": "ph-fill", "nodeId": "ph", "name": "fill", "children": ["mine"]}
         },
         "themeRef": {"type": "inherit"}}
    """.trimIndent()

    private fun textNode(id: String, text: String) = """
        "$id": {"id": "$id", "type": "text", "slots": [], "props": {"content":
          {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "$text"}]}]}}}
    """.trimIndent()

    private companion object {
        /** The letterhead stencil: a logo line, then a `body` placeholder. */
        val LETTERHEAD = """
            {"modelVersion": 1, "root": "root",
             "nodes": {
               "root": {"id": "root", "type": "root", "slots": ["slot-root"]},
               "logo": {"id": "logo", "type": "text", "slots": [], "props": {"content":
                 {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "ACME"}]}]}}},
               "body": {"id": "body", "type": "placeholder", "slots": ["body-default", "body-fill"], "props": {"name": "body"}}
             },
             "slots": {
               "slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": ["logo", "body"]},
               "body-default": {"id": "body-default", "nodeId": "body", "name": "default", "children": []},
               "body-fill": {"id": "body-fill", "nodeId": "body", "name": "fill", "children": []}
             },
             "themeRef": {"type": "inherit"}}
        """.trimIndent()

        const val NAME_SCHEMA = """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}"""
    }
}

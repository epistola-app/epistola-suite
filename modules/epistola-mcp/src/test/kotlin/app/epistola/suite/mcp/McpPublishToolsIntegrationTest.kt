// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.DataContractMcpTools
import app.epistola.suite.mcp.tools.PublishMcpTools
import app.epistola.suite.mcp.tools.StencilMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mcp.tools.VersionMcpTools
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.template.model.TemplateDocument
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The MCP publish tools: taking a catalog built over MCP from drafts to published versions — stencils,
 * the relink of template instances built against stencil drafts, data contracts and template versions.
 */
class McpPublishToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var publishTools: PublishMcpTools

    @Autowired private lateinit var templateTools: TemplateMcpTools

    @Autowired private lateinit var versionTools: VersionMcpTools

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

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Publish ${UUID.randomUUID()}").id)

    /** A stencil `letterhead` (draft v1) and a template `letter` embedding it by its draft. */
    private fun seedTemplateOnStencilDraft(tenantId: TenantId): String = runAsApiKey(tenantId) {
        stencilTools.createStencil("default", "letterhead", "Letterhead", null, null, document("text-1", "ACME"), null)
        templateTools.createTemplate("default", "letter", "Letter", null, null, null)
        contractTools.updateDataContract("default", "letter", NAME_SCHEMA, """[{"name": "Jane", "data": {"name": "Jane"}}]""")
        val variant = templateTools.listVariants("default", "letter").single().id
        versionTools.updateTemplateDraft("default", "letter", variant, templateWithStencil(""""draftVersion": 1"""))
        variant
    }

    @Test
    fun `a template built on a stencil draft is published through stencil publish, upgrade and template publish`() {
        val tenantId = newTenant()
        val variant = seedTemplateOnStencilDraft(tenantId)

        assertThatThrownBy {
            runAsApiKey(tenantId) { publishTools.publishTemplateVersion("default", "letter", variant, null) }
        }.hasMessageContaining("cannot reference draft stencil versions")

        val stencil = runAsApiKey(tenantId) { publishTools.publishStencilVersion("default", "letterhead", 1) }
        assertThat(stencil.status).isEqualTo("published")
        assertThat(stencil.publishedAt).isNotNull

        val upgrade = runAsApiKey(tenantId) { publishTools.upgradeStencilInTemplate("default", "letter", variant, "letterhead", 1) }
        assertThat(upgrade.upgradedCount).isEqualTo(1)
        assertThat(upgrade.unboundRequired).isEmpty()
        val model = runAsApiKey(tenantId) { templateTools.getTemplateContent("default", "letter", variant) }!!
            .templateModel as TemplateDocument
        val instance = model.nodes.getValue("stencil-1").props!!
        assertThat(instance["version"]).isEqualTo(1)
        assertThat(instance).doesNotContainKey("draftVersion")

        val published = runAsApiKey(tenantId) { publishTools.publishTemplateVersion("default", "letter", variant, null) }
        assertThat(published.status).isEqualTo("published")
        assertThat(published.publishedAt).isNotNull
        assertThat(runAsApiKey(tenantId) { versionTools.listVersions("default", "letter", variant) }.map { it.status })
            .containsExactly("published")
        assertThat(runAsApiKey(tenantId) { contractTools.getDataContract("default", "letter", null) }!!.status)
            .isEqualTo("published")
    }

    @Test
    fun `publish_stencil_version refuses a version that is already published`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) { stencilTools.createStencil("default", "letterhead", "Letterhead", null, null, null, null) }
        runAsApiKey(tenantId) { publishTools.publishStencilVersion("default", "letterhead", 1) }

        assertThatThrownBy {
            runAsApiKey(tenantId) { publishTools.publishStencilVersion("default", "letterhead", 1) }
        }.hasMessageContaining("already published")
    }

    @Test
    fun `upgrade_stencil_in_template refuses an unpublished stencil version`() {
        val tenantId = newTenant()
        val variant = seedTemplateOnStencilDraft(tenantId)

        assertThatThrownBy {
            runAsApiKey(tenantId) { publishTools.upgradeStencilInTemplate("default", "letter", variant, "letterhead", 1) }
        }.hasMessageContaining("not published")
    }

    @Test
    fun `publish_data_contract previews a breaking change and publishes it only when confirmed`() {
        val tenantId = newTenant()
        runAsApiKey(tenantId) {
            templateTools.createTemplate("default", "letter", "Letter", null, null, null)
            contractTools.updateDataContract("default", "letter", NAME_SCHEMA, """[{"name": "Jane", "data": {"name": "Jane"}}]""")
        }
        assertThat(runAsApiKey(tenantId) { publishTools.publishDataContract("default", "letter", null) }.published).isTrue

        // `name` removed: breaking for anything that read it.
        runAsApiKey(tenantId) {
            contractTools.updateDataContract(
                "default",
                "letter",
                """{"type": "object", "properties": {"fullName": {"type": "string"}}}""",
                """[{"name": "Jane", "data": {"fullName": "Jane"}}]""",
            )
        }

        val preview = runAsApiKey(tenantId) { publishTools.publishDataContract("default", "letter", false) }
        assertThat(preview.published).isFalse
        assertThat(preview.compatible).isFalse
        assertThat(preview.breakingChanges.map { it.path }).anyMatch { it.contains("name") }

        val confirmed = runAsApiKey(tenantId) { publishTools.publishDataContract("default", "letter", true) }
        assertThat(confirmed.published).isTrue
        assertThat(confirmed.publishedVersion).isEqualTo(2)
    }

    @Test
    fun `publishing needs the publish permissions`() {
        val tenantId = newTenant()
        val variant = seedTemplateOnStencilDraft(tenantId)
        val authorOnly = setOf(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)

        assertThatThrownBy {
            runAsApiKey(tenantId, authorOnly) { publishTools.publishStencilVersion("default", "letterhead", 1) }
        }.hasMessageContaining("STENCIL_PUBLISH")
        assertThatThrownBy {
            runAsApiKey(tenantId, authorOnly) { publishTools.publishTemplateVersion("default", "letter", variant, null) }
        }.hasMessageContaining("TEMPLATE_PUBLISH")
    }

    @Test
    fun `publish tools refuse a SUBSCRIBED catalog`() {
        val tenantId = newTenant()

        assertThatThrownBy {
            runAsApiKey(tenantId) { publishTools.upgradeStencilInTemplate("system", "letter", "initial", "letterhead", 1) }
        }.hasMessageContaining("read-only catalog 'system'")
        assertThatThrownBy {
            runAsApiKey(tenantId) { publishTools.publishDataContract("system", "letter", null) }
        }.hasMessageContaining("read-only catalog 'system'")
    }

    private fun document(textId: String, text: String): String = """
        {"modelVersion": 1, "root": "root",
         "nodes": {"root": {"id": "root", "type": "root", "slots": ["slot-root"]}, ${textNode(textId, text)}},
         "slots": {"slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": ["$textId"]}},
         "themeRef": {"type": "inherit"}}
    """.trimIndent()

    /** A template whose root holds one `letterhead` stencil instance carrying a copy of its content. */
    private fun templateWithStencil(reference: String): String = """
        {"modelVersion": 1, "root": "root",
         "nodes": {
           "root": {"id": "root", "type": "root", "slots": ["slot-root"]},
           "stencil-1": {"id": "stencil-1", "type": "stencil", "slots": ["slot-stencil"],
                         "props": {"stencilId": "letterhead", "catalogKey": "default", $reference}},
           ${textNode("copy-1", "ACME")}
         },
         "slots": {
           "slot-root": {"id": "slot-root", "nodeId": "root", "name": "children", "children": ["stencil-1"]},
           "slot-stencil": {"id": "slot-stencil", "nodeId": "stencil-1", "name": "children", "children": ["copy-1"]}
         },
         "themeRef": {"type": "inherit"}}
    """.trimIndent()

    private fun textNode(id: String, text: String) = """
        "$id": {"id": "$id", "type": "text", "slots": [], "props": {"content":
          {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": "$text"}]}]}}}
    """.trimIndent()

    private companion object {
        const val NAME_SCHEMA = """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}"""
    }
}

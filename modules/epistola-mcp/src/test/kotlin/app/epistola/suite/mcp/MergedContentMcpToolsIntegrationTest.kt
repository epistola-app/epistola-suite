// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.merge.MergeEntryNotFoundException
import app.epistola.suite.catalog.merge.MergeRefusedException
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mcp.tools.CatalogReleaseMcpTools
import app.epistola.suite.mcp.tools.ReadyMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * A smart merge over MCP (contract ADR 0005): an assistant previews a merge, reads the sides of a
 * conflict with `get_merge_sides`, combines them, and merges the result with `merge_release`'s
 * `mergedContent`.
 *
 * `letters` 1.0.0 has the template `invoice` with the variants `initial` and `english`; parallel
 * work retitles `english` and is released as 1.1.0, while the next release retitles `initial`.
 */
class MergedContentMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var versionTools: CatalogReleaseMcpTools

    @Autowired private lateinit var templateTools: TemplateMcpTools

    @Autowired private lateinit var readyTools: ReadyMcpTools

    @Autowired private lateinit var objectMapper: ObjectMapper

    private val letters = CatalogKey.of("letters")

    private fun <T> runAsApiKey(tenantId: TenantId, block: () -> T): T = UUID.randomUUID().let { keyId ->
        runAs(
            EpistolaPrincipal(
                userId = UserKey.of(keyId),
                externalId = "apikey:$keyId",
                email = "apikey-$keyId@npa.epistola",
                displayName = "Test API Key",
                tenantMemberships = mapOf(tenantId.key to TenantRole.entries.toSet()),
                globalRoles = emptySet(),
                platformRoles = emptySet(),
                currentTenantId = tenantId.key,
            ),
            block,
        )
    }

    private fun bothRetitled(name: String): TenantId {
        val tenant = createTenant("$name ${UUID.randomUUID()}").id
        val invoice = TemplateId(TemplateKey.of("invoice"), testCatalog(TenantId(tenant), letters))
        fun variant(key: String) = VariantId(VariantKey.of(key), invoice)
        withMediator {
            CreateCatalog(tenantKey = tenant, id = letters, name = "Letters").execute()
            CreateDocumentTemplate(id = invoice, name = "Invoice").execute()
            CreateVariant(variant("english"), title = "English", description = null).execute()
            MarkCatalogReady(testCatalog(tenant, letters)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.0.0").execute()
            val fixRef = CatalogReleaseRef(testCatalog(tenant, letters), DraftRef.Id(CreateCatalogDraft(testCatalog(tenant, letters), "Fix", "1.0.0").execute().versionId))
            UpdateVariant(variant("english"), "English (fixed)", emptyMap(), catalogVersion = fixRef).execute()
            MarkCatalogReady(testCatalog(tenant, letters), catalogVersion = fixRef).execute()
            ReleaseCatalogDraft(catalog = testCatalog(tenant, letters), version = "1.1.0", catalogVersion = fixRef).execute()
            UpdateVariant(variant("initial"), "Standard", emptyMap()).execute()
        }
        return TenantId(tenant)
    }

    @Suppress("UNCHECKED_CAST")
    private fun variants(side: Map<String, Any?>?): List<Map<String, Any?>> = side!!["variants"] as List<Map<String, Any?>>

    @Test
    fun `a conflicting template whose sides changed different variants is merged keeping both changes`() {
        val tenantId = bothRetitled("MCP Smart Merge")
        val preview = runAsApiKey(tenantId) { versionTools.previewMerge("letters", "1.1.0", null) }
        assertThat(preview.resources.map { it.resource to it.outcome }).containsExactly("template/invoice" to "CONFLICT")

        val sides = runAsApiKey(tenantId) { versionTools.getMergeSides("letters", "1.1.0", "template/invoice", null) }
        assertThat(sides.mergeBase).isEqualTo("1.0.0")
        assertThat(sides.token).isEqualTo(preview.token)
        assertThat(variants(sides.base).associate { it["key"] to it["name"] }).containsEntry("initial", "Default").containsEntry("english", "English")

        // What an assistant does: each variant as the side that changed it has it.
        val base = variants(sides.base).associateBy { it["key"] }
        val theirs = variants(sides.release).associateBy { it["key"] }
        val combined = sides.draft!! + ("variants" to variants(sides.draft).map { ours -> if (ours == base[ours["key"]]) theirs.getValue(ours["key"]) else ours })

        // Given as an object string, as a client that sends JSON arguments as text would.
        val merged = runAsApiKey(tenantId) {
            versionTools.mergeRelease("letters", "1.1.0", null, null, sides.token, mergedContent = mapOf("template/invoice" to objectMapper.writeValueAsString(combined)))
        }

        assertThat(merged.merged).containsExactly("template/invoice")
        assertThat(merged.taken).isEmpty()
        assertThat(merged.notice).contains("not marked ready").contains("template/invoice")
        runAsApiKey(tenantId) {
            assertThat(templateTools.listVariants("letters", "invoice").associate { it.id to it.title })
                .containsExactlyInAnyOrderEntriesOf(mapOf("initial" to "Standard", "english" to "English (fixed)"))
            val invoice = readyTools.getCatalogChanges("letters").resources.single { it.type == "template" && it.key == "invoice" }
            assertThat(invoice.state).isEqualTo("MODIFIED")
            assertThat(invoice.ready).`as`("to be reviewed before releasing").isFalse()
            assertThat(invoice.mergedForReview).`as`("merged content, flagged for review").isTrue()
            assertThat(versionTools.previewMerge("letters", "1.1.0", null).alreadyMerged).isTrue()
        }
    }

    @Test
    fun `merged content that is not an object, not valid, or names what the result lacks is refused`() {
        val tenantId = bothRetitled("MCP Smart Merge Refused")
        val sides = runAsApiKey(tenantId) { versionTools.getMergeSides("letters", "1.1.0", "template/invoice", null) }

        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.mergeRelease("letters", "1.1.0", null, null, mergedContent = mapOf("template/invoice" to "not json")) } }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.field).isEqualTo("mergedContent.template/invoice") }
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.mergeRelease("letters", "1.1.0", null, null, mergedContent = mapOf("template/invoice" to sides.draft!! + ("key" to "other"))) } }
            .isInstanceOfSatisfying(ValidationException::class.java) { assertThat(it.field).isEqualTo("mergedContent.template/invoice/key") }
        assertThatThrownBy {
            runAsApiKey(tenantId) { versionTools.mergeRelease("letters", "1.1.0", null, mapOf("template/invoice" to "OURS"), mergedContent = mapOf("template/invoice" to sides.draft)) }
        }.isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.mergeRelease("letters", "1.1.0", null, null, mergedContent = mapOf("template/invoice" to sides.draft!! + ("themeKey" to "ghost"))) } }
            .isInstanceOf(MergeRefusedException::class.java)
            .hasMessageContaining("template 'invoice' uses theme 'ghost', which this catalog does not have")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.getMergeSides("letters", "1.1.0", "theme/nothing", null) } }
            .isInstanceOf(MergeEntryNotFoundException::class.java)
    }
}

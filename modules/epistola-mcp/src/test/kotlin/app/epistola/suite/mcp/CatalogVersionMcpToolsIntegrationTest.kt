// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.merge.MergeBaseMissingException
import app.epistola.suite.catalog.merge.MergePreviewOutdatedException
import app.epistola.suite.catalog.versions.CatalogDraftIsNextException
import app.epistola.suite.catalog.versions.CatalogVersionHeldException
import app.epistola.suite.catalog.versions.CatalogVersionNotFoundException
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.CatalogVersionMcpTools
import app.epistola.suite.mcp.tools.ReadyMcpTools
import app.epistola.suite.mcp.tools.TemplateMcpTools
import app.epistola.suite.mcp.tools.ThemeMcpTools
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.parallelReleaseLines
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The drafts of a catalog over MCP (contract ADR 0002, amendment 2): the version tools list drafts and
 * releases, start, rename and discard parallel work, preview the number the next version gets and
 * release it; the content tools read and write the draft their `catalogVersion` argument names, the
 * next version without one.
 */
class CatalogVersionMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var versionTools: CatalogVersionMcpTools

    @Autowired private lateinit var themeTools: ThemeMcpTools

    @Autowired private lateinit var templateTools: TemplateMcpTools

    @Autowired private lateinit var readyTools: ReadyMcpTools

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

    /** The default catalog with a `brand` theme, released; returns the number it was released as. */
    private fun released(tenantId: TenantId): String = runAsApiKey(tenantId) {
        themeTools.createTheme("default", "brand", "Brand", null, null, null, null, null)
        readyTools.markCatalogReady("default")
        versionTools.releaseDraft("default", null, null, "first", null, null).version
    }

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Versions ${UUID.randomUUID()}").id)

    /** Starts a draft called [name]; its `versionId`. */
    private fun work(tenantId: TenantId, name: String): String = runAsApiKey(tenantId) { versionTools.startDraft("default", name, null) }.versionId

    @Test
    fun `a draft is started from the highest release and listed after the next version, without a number`() {
        val tenantId = newTenant()
        val first = released(tenantId)

        val created = runAsApiKey(tenantId) { versionTools.startDraft("default", "Redesign", null) }

        assertThat(created.status).isEqualTo("DRAFT")
        assertThat(created.version).isNull()
        assertThat(created.isNext).isFalse()
        assertThat(created.name).isEqualTo("Redesign")
        assertThat(created.base).isEqualTo(first)
        val versions = runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", null) }
        assertThat(versions.map { Triple(it.isNext, it.name, it.version) }).containsExactly(
            Triple(true, "Next release", null),
            Triple(false, "Redesign", null),
            Triple(false, null, first),
        )
        assertThat(versions.map { it.versionId }).doesNotHaveDuplicates().allMatch { it.matches(Regex("^[a-z0-9]{8}$")) }
    }

    @Test
    fun `content tools read and write the draft catalogVersion names, and the next version without it`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")

        runAsApiKey(tenantId) {
            themeTools.updateTheme("default", "brand", "Brand, fixed", null, null, null, null, null, null, null, catalogVersion = "next")
            themeTools.updateTheme("default", "brand", "Brand redesigned", null, null, null, null, null, null, null, catalogVersion = redesign)
        }

        runAsApiKey(tenantId) {
            assertThat(themeTools.getTheme("default", "brand", catalogVersion = redesign)!!.name).isEqualTo("Brand redesigned")
            // Without one, the next version, however new parallel work is.
            assertThat(themeTools.getTheme("default", "brand")!!.name).isEqualTo("Brand, fixed")
            assertThat(themeTools.listThemes("default", null, catalogVersion = redesign).map { it.name }).contains("Brand redesigned")
            assertThat(readyTools.getCatalogChanges("default", catalogVersion = redesign).resources.single { it.key == "brand" }.state).isEqualTo("MODIFIED")
        }
    }

    @Test
    fun `a template created in parallel work exists only there`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")

        runAsApiKey(tenantId) { templateTools.createTemplate("default", "invoice", "Invoice", null, null, null, catalogVersion = redesign) }

        runAsApiKey(tenantId) {
            assertThat(templateTools.getTemplate("default", "invoice", catalogVersion = redesign)).isNotNull
            assertThat(templateTools.getTemplate("default", "invoice", catalogVersion = "next")).isNull()
            assertThat(templateTools.listTemplates("default", null).map { it.id }).doesNotContain("invoice")
        }
    }

    @Test
    fun `a draft without a catalog, a number, and a draft the catalog does not have, are refused`() {
        val tenantId = newTenant()

        assertThatThrownBy { runAsApiKey(tenantId) { themeTools.listThemes(null, null, catalogVersion = "next") } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("catalogId")
        assertThatThrownBy { runAsApiKey(tenantId) { themeTools.getTheme("default", "brand", catalogVersion = "1.0.0") } }
            .`as`("a draft has no number").isInstanceOf(ValidationException::class.java)
        assertThatThrownBy { runAsApiKey(tenantId) { themeTools.getTheme("default", "brand", catalogVersion = UUID.randomUUID().toString()) } }
            .isInstanceOf(CatalogVersionNotFoundException::class.java)
    }

    @Test
    fun `a draft is renamed, and the next version has no name to change`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")

        val renamed = runAsApiKey(tenantId) { versionTools.renameDraft("default", redesign, "Redesign 2027") }

        assertThat(renamed.name).isEqualTo("Redesign 2027")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.renameDraft("default", "next", "Mine") } }.isInstanceOf(CatalogDraftIsNextException::class.java)
    }

    @Test
    fun `the release number comes from what changed, with the reasons, and releasing uses it`() {
        val tenantId = newTenant()
        val first = released(tenantId)
        runAsApiKey(tenantId) {
            themeTools.createTheme("default", "extra", "Extra", null, null, null, null, null)
            readyTools.markCatalogReady("default")
        }

        val preview = runAsApiKey(tenantId) { versionTools.previewReleaseNumber("default", null) }

        assertThat(preview.base).isEqualTo(first)
        assertThat(preview.needed).isEqualTo("1.1.0")
        assertThat(preview.changeKind).isEqualTo("ADDITIVE")
        assertThat(preview.reasons).containsExactly("theme 'extra': added")
        assertThat(preview.suggested).isEqualTo("1.1.0")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.releaseDraft("default", null, "1.0.1", null, null, null) } }
            .`as`("never lower than the changes need").isInstanceOf(CatalogReleaseVersionException::class.java)
        assertThat(runAsApiKey(tenantId) { versionTools.releaseDraft("default", null, null, null, null, null) }.version).isEqualTo("1.1.0")
    }

    @Test
    fun `releasing the next version continues it, and another draft is behind the release`() {
        val tenantId = newTenant()
        released(tenantId)
        work(tenantId, "Redesign")

        val release = runAsApiKey(tenantId) {
            themeTools.updateTheme("default", "brand", "Brand, fixed", null, null, null, null, null, null, null)
            readyTools.markCatalogReady("default")
            versionTools.releaseDraft("default", null, null, null, null, null)
        }

        assertThat(release.version).isEqualTo("1.0.1")
        assertThat(release.draftsBehind).containsExactly("Redesign")
        val drafts = runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", "DRAFT") }
        assertThat(drafts.single { it.isNext }.base).isEqualTo("1.0.1")
        val redesign = drafts.single { !it.isNext }
        assertThat(redesign.behind.map { it.version }).containsExactly("1.0.1")
        assertThat(redesign.behind.single().changes).containsExactly("theme/brand modified")
    }

    @Test
    fun `discarding a draft removes it, and the next version holding the default theme alone is refused`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")
        runAsApiKey(tenantId) { themeTools.createTheme("default", "seasonal", "Seasonal", null, null, null, null, null) }
        withMediator { SetTenantDefaultTheme(tenantId.key, ThemeKey.of("seasonal"), CatalogKey.of("default")).execute() }

        val left = runAsApiKey(tenantId) { versionTools.discardDraft("default", redesign) }

        assertThat(left.map { it.name }).doesNotContain("Redesign")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.discardDraft("default", "next") } }
            .isInstanceOf(CatalogVersionHeldException::class.java)
            .hasMessageContaining("default theme")
    }

    @Test
    fun `parallel work released for everyday work becomes the next version, keeping the old one by name`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")
        runAsApiKey(tenantId) { themeTools.updateTheme("default", "brand", "Brand redesigned", null, null, null, null, null, null, null, catalogVersion = redesign) }

        val release = runAsApiKey(tenantId) {
            readyTools.markCatalogReady("default", catalogVersion = redesign)
            versionTools.releaseDraft("default", redesign, null, null, null, null, continueEverydayWork = true, keepPreviousAs = "Old next")
        }

        assertThat(release.versionId).isEqualTo(redesign)
        assertThat(runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", "DRAFT") }.map { it.name to it.isNext })
            .containsExactlyInAnyOrder("Next release" to true, "Old next" to false)
        assertThat(runAsApiKey(tenantId) { themeTools.getTheme("default", "brand")!!.name }).isEqualTo("Brand redesigned")
    }

    @Test
    fun `a release the next version is behind is previewed and merged, its conflict resolved as the user chose`() {
        val tenantId = newTenant()
        released(tenantId)
        val fix = work(tenantId, "Fix")
        runAsApiKey(tenantId) {
            themeTools.updateTheme("default", "brand", "Brand, fixed", null, null, null, null, null, null, null, catalogVersion = fix)
            readyTools.markCatalogReady("default", catalogVersion = fix)
            versionTools.releaseDraft("default", fix, null, "the fix", null, null)
            themeTools.updateTheme("default", "brand", "Brand, ours", null, null, null, null, null, null, null)
        }
        val fixRelease = runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", "RELEASED") }.first().version!!

        val preview = runAsApiKey(tenantId) { versionTools.previewMerge("default", fixRelease, null) }
        assertThat(preview.resources.map { it.resource to it.outcome }).containsExactly("theme/brand" to "CONFLICT")

        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.mergeRelease("default", fixRelease, null, mapOf("theme/brand" to "MINE")) } }
            .isInstanceOf(ValidationException::class.java)
        runAsApiKey(tenantId) { themeTools.updateTheme("default", "brand", "Brand, ours again", null, null, null, null, null, null, null) }
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.mergeRelease("default", fixRelease, null, mapOf("theme/brand" to "theirs"), preview.token) } }
            .`as`("the draft changed since the preview")
            .isInstanceOf(MergePreviewOutdatedException::class.java)
        val fresh = runAsApiKey(tenantId) { versionTools.previewMerge("default", fixRelease, null) }
        val merged = runAsApiKey(tenantId) { versionTools.mergeRelease("default", fixRelease, null, mapOf("theme/brand" to "theirs"), fresh.token) }

        assertThat(merged.taken).containsExactly("theme/brand")
        runAsApiKey(tenantId) {
            assertThat(themeTools.getTheme("default", "brand")!!.name).isEqualTo("Brand, fixed")
            assertThat(versionTools.previewMerge("default", fixRelease, null).alreadyMerged).isTrue()
            assertThat(versionTools.listCatalogVersions("default", "DRAFT").single { it.isNext }.behind).isEmpty()
        }
    }

    @Test
    fun `a merge whose merge base this installation lacks names the release to get, and is refused`() {
        val producer = createTenant("MCP Lineage Producer ${UUID.randomUUID()}").id
        val tenantId = newTenant()
        withMediator {
            val lines = mediator.parallelReleaseLines(producer, CatalogKey.of("mcp-lines"))
            ImportCatalogZip(tenantKey = tenantId.key, zipBytes = lines.v110).execute()
            ImportCatalogZip(tenantKey = tenantId.key, zipBytes = lines.v101).execute()
        }

        val preview = runAsApiKey(tenantId) { versionTools.previewMerge("mcp-lines", "1.0.1", null) }
        assertThat(preview.missingBase?.version).isEqualTo("1.0.0")
        assertThat(preview.resources).isEmpty()
        assertThat(preview.notice).isEqualTo("To merge v1.0.1, this installation needs v1.0.0, the release both come from. Get it first (from a registry, the Exchange or a file).")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.mergeRelease("mcp-lines", "1.0.1", null, null) } }
            .isInstanceOf(MergeBaseMissingException::class.java)
            .hasMessageContaining("needs v1.0.0")
    }
}

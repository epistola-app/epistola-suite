// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.commands.CatalogReleaseVersionException
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
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The drafts of a catalog over MCP (contract ADR 0002, amendment 2): the version tools list drafts and
 * releases, start, rename and discard long-term work, preview the number the next version gets and
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
        versionTools.releaseNextVersion("default", null, "first", null).version
    }

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Versions ${UUID.randomUUID()}").id)

    /** Starts long-term work called [name]; its `draftId`. */
    private fun work(tenantId: TenantId, name: String): String = runAsApiKey(tenantId) { versionTools.startLongTermWork("default", name, null) }.draftId!!

    @Test
    fun `long-term work is started from the next version and listed after it, without a number`() {
        val tenantId = newTenant()
        val first = released(tenantId)

        val created = runAsApiKey(tenantId) { versionTools.startLongTermWork("default", "Redesign", null) }

        assertThat(created.status).isEqualTo("DRAFT")
        assertThat(created.version).isNull()
        assertThat(created.isNext).isFalse()
        assertThat(created.name).isEqualTo("Redesign")
        assertThat(created.base).isEqualTo(first)
        val versions = runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", null) }
        assertThat(versions.map { Triple(it.isNext, it.name, it.version) }).containsExactly(
            Triple(true, null, null),
            Triple(false, "Redesign", null),
            Triple(false, null, first),
        )
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
            // Without one, the next version, however new long-term work is.
            assertThat(themeTools.getTheme("default", "brand")!!.name).isEqualTo("Brand, fixed")
            assertThat(themeTools.listThemes("default", null, catalogVersion = redesign).map { it.name }).contains("Brand redesigned")
            assertThat(readyTools.getCatalogChanges("default", catalogVersion = redesign).resources.single { it.key == "brand" }.state).isEqualTo("MODIFIED")
        }
    }

    @Test
    fun `a template created in long-term work exists only there`() {
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
    fun `long-term work is renamed, and the next version has no name to change`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")

        val renamed = runAsApiKey(tenantId) { versionTools.renameLongTermWork("default", redesign, "Redesign 2027") }

        assertThat(renamed.name).isEqualTo("Redesign 2027")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.renameLongTermWork("default", "next", "Mine") } }.isInstanceOf(ValidationException::class.java)
    }

    @Test
    fun `the release number comes from what changed, with the reasons, and releasing uses it`() {
        val tenantId = newTenant()
        val first = released(tenantId)
        runAsApiKey(tenantId) {
            themeTools.createTheme("default", "extra", "Extra", null, null, null, null, null)
            readyTools.markCatalogReady("default")
        }

        val preview = runAsApiKey(tenantId) { versionTools.previewReleaseNumber("default") }

        assertThat(preview.latest).isEqualTo(first)
        assertThat(preview.changeKind).isEqualTo("ADDITIVE")
        assertThat(preview.reasons).containsExactly("theme 'extra': added")
        assertThat(preview.suggested).isEqualTo("1.1.0")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.releaseNextVersion("default", "1.0.1", null, null) } }
            .`as`("never lower than the changes need").isInstanceOf(CatalogReleaseVersionException::class.java)
        assertThat(runAsApiKey(tenantId) { versionTools.releaseNextVersion("default", null, null, null) }.version).isEqualTo("1.1.0")
    }

    @Test
    fun `releasing the next version continues it, and long-term work is behind the release`() {
        val tenantId = newTenant()
        released(tenantId)
        work(tenantId, "Redesign")

        val release = runAsApiKey(tenantId) {
            themeTools.updateTheme("default", "brand", "Brand, fixed", null, null, null, null, null, null, null)
            readyTools.markCatalogReady("default")
            versionTools.releaseNextVersion("default", null, null, null)
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
    fun `discarding long-term work removes it, discarding the next version starts it again, and one holding the default theme alone is refused`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")
        runAsApiKey(tenantId) { themeTools.createTheme("default", "seasonal", "Seasonal", null, null, null, null, null) }
        withMediator { SetTenantDefaultTheme(tenantId.key, ThemeKey.of("seasonal"), CatalogKey.of("default")).execute() }

        val left = runAsApiKey(tenantId) { versionTools.discardCatalogWork("default", redesign) }

        assertThat(left.map { it.name }).doesNotContain("Redesign")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.discardCatalogWork("default", "next") } }
            .isInstanceOf(CatalogVersionHeldException::class.java)
            .hasMessageContaining("default theme")
    }

    @Test
    fun `long-term work is made the next version, keeping the old one by name`() {
        val tenantId = newTenant()
        released(tenantId)
        val redesign = work(tenantId, "Redesign")
        runAsApiKey(tenantId) { themeTools.updateTheme("default", "brand", "Brand redesigned", null, null, null, null, null, null, null, catalogVersion = redesign) }

        val promoted = runAsApiKey(tenantId) { versionTools.promoteLongTermWork("default", redesign, "Old next", null) }

        assertThat(promoted.isNext).isTrue()
        assertThat(runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", "DRAFT") }.map { it.name }).containsExactly(null, "Old next")
        assertThat(runAsApiKey(tenantId) { themeTools.getTheme("default", "brand")!!.name }).isEqualTo("Brand redesigned")
    }
}

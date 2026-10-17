// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.catalog.versions.CatalogVersionBehindException
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
 * Several drafts of a catalog over MCP (contract ADR 0002): the version tools list, open, renumber,
 * release and discard drafts, and the content tools read and write the draft their `catalogVersion`
 * argument names.
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

    /** The default catalog with a `brand` theme, released under its first draft's number; returns that number. */
    private fun released(tenantId: TenantId): String = runAsApiKey(tenantId) {
        themeTools.createTheme("default", "brand", "Brand", null, null, null, null, null)
        readyTools.markCatalogReady("default")
        val draft = versionTools.listCatalogVersions("default", "DRAFT").single().version
        versionTools.releaseCatalogVersion("default", draft, "first", null)
        draft
    }

    private fun newTenant(): TenantId = TenantId(createTenant("MCP Versions ${UUID.randomUUID()}").id)

    @Test
    fun `a second draft is opened from the last release and listed with its base`() {
        val tenantId = newTenant()
        val first = released(tenantId)

        val created = runAsApiKey(tenantId) { versionTools.createCatalogVersion("default", "2.0.0", null) }

        assertThat(created.status).isEqualTo("DRAFT")
        assertThat(created.base).isEqualTo(first)
        val versions = runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", null) }
        assertThat(versions.first().version).isEqualTo("2.0.0")
        assertThat(versions.filter { it.status == "DRAFT" }.map { it.version }).hasSize(2).contains("2.0.0")
        assertThat(versions.filter { it.status == "RELEASED" }.map { it.version }).containsExactly(first)
    }

    @Test
    fun `content tools read and write the draft catalogVersion names, and the current one without it`() {
        val tenantId = newTenant()
        released(tenantId)
        val lower = runAsApiKey(tenantId) {
            versionTools.createCatalogVersion("default", "2.0.0", null)
            versionTools.listCatalogVersions("default", "DRAFT").map { it.version }.single { it != "2.0.0" }
        }

        runAsApiKey(tenantId) {
            themeTools.updateTheme("default", "brand", "Brand, maintenance", null, null, null, null, null, null, null, catalogVersion = lower)
            themeTools.updateTheme("default", "brand", "Brand two", null, null, null, null, null, null, null, catalogVersion = "2.0.0")
        }

        runAsApiKey(tenantId) {
            assertThat(themeTools.getTheme("default", "brand", catalogVersion = lower)!!.name).isEqualTo("Brand, maintenance")
            assertThat(themeTools.getTheme("default", "brand", catalogVersion = "2.0.0")!!.name).isEqualTo("Brand two")
            // Without one, the catalog's current version: its highest draft.
            assertThat(themeTools.getTheme("default", "brand")!!.name).isEqualTo("Brand two")
            assertThat(themeTools.listThemes("default", null, catalogVersion = lower).map { it.name }).contains("Brand, maintenance")
            assertThat(readyTools.getCatalogChanges("default", catalogVersion = lower).resources.single { it.key == "brand" }.state).isEqualTo("MODIFIED")
        }
    }

    @Test
    fun `a template created in a named draft exists only there`() {
        val tenantId = newTenant()
        released(tenantId)
        val lower = runAsApiKey(tenantId) {
            versionTools.createCatalogVersion("default", "2.0.0", null)
            versionTools.listCatalogVersions("default", "DRAFT").map { it.version }.single { it != "2.0.0" }
        }

        runAsApiKey(tenantId) { templateTools.createTemplate("default", "invoice", "Invoice", null, null, null, catalogVersion = lower) }

        runAsApiKey(tenantId) {
            assertThat(templateTools.getTemplate("default", "invoice", catalogVersion = lower)).isNotNull
            assertThat(templateTools.getTemplate("default", "invoice", catalogVersion = "2.0.0")).isNull()
            assertThat(templateTools.listTemplates("default", null, catalogVersion = lower).map { it.id }).contains("invoice")
            assertThat(templateTools.listTemplates("default", null).map { it.id }).doesNotContain("invoice")
        }
    }

    @Test
    fun `a version without a catalog, and a version the catalog does not have, are refused`() {
        val tenantId = newTenant()

        assertThatThrownBy { runAsApiKey(tenantId) { themeTools.listThemes(null, null, catalogVersion = "2.0.0") } }
            .isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("catalogId")
        assertThatThrownBy { runAsApiKey(tenantId) { themeTools.getTheme("default", "brand", catalogVersion = "9.9.9") } }
            .isInstanceOf(CatalogVersionNotFoundException::class.java)
    }

    @Test
    fun `renumbering past another draft returns its warnings first, then renumbers when acknowledged`() {
        val tenantId = newTenant()
        released(tenantId)
        val lower = runAsApiKey(tenantId) {
            versionTools.createCatalogVersion("default", "1.5.0", null)
            versionTools.listCatalogVersions("default", "DRAFT").map { it.version }.single { it != "1.5.0" }
        }

        val warned = runAsApiKey(tenantId) { versionTools.renumberCatalogVersion("default", lower, "1.6.0", null) }

        assertThat(warned.renumbered).isFalse
        assertThat(warned.warnings.map { it.kind }).containsExactly("passes-draft")
        val renumbered = runAsApiKey(tenantId) { versionTools.renumberCatalogVersion("default", lower, "1.6.0", true) }
        assertThat(renumbered.renumbered).isTrue
        assertThat(renumbered.version!!.version).isEqualTo("1.6.0")
    }

    @Test
    fun `releasing a draft behind a release needs it acknowledged, and the lower draft is discarded on release`() {
        val tenantId = newTenant()
        released(tenantId)
        val lower = runAsApiKey(tenantId) {
            versionTools.createCatalogVersion("default", "2.0.0", null)
            versionTools.listCatalogVersions("default", "DRAFT").map { it.version }.single { it != "2.0.0" }
        }
        val lowerRelease = runAsApiKey(tenantId) {
            themeTools.updateTheme("default", "brand", "Brand, fixed", null, null, null, null, null, null, null, catalogVersion = lower)
            readyTools.markCatalogReady("default", catalogVersion = lower)
            versionTools.releaseCatalogVersion("default", lower, null, null)
        }
        assertThat(lowerRelease.openedDraft).isNull()
        assertThat(lowerRelease.draftsBehind).containsExactly("2.0.0")
        val draft = runAsApiKey(tenantId) { versionTools.listCatalogVersions("default", "DRAFT") }.single()
        assertThat(draft.behind.map { it.version }).containsExactly(lower)
        assertThat(draft.behind.single().changes).containsExactly("theme/brand modified")

        runAsApiKey(tenantId) { readyTools.markCatalogReady("default", catalogVersion = "2.0.0") }
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.releaseCatalogVersion("default", "2.0.0", null, null) } }
            .isInstanceOf(CatalogVersionBehindException::class.java)
        val higher = runAsApiKey(tenantId) { versionTools.releaseCatalogVersion("default", "2.0.0", null, listOf(lower)) }
        assertThat(higher.openedDraft).isEqualTo("2.0.1")
        // A number no draft has is refused rather than releasing the current draft renumbered.
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.releaseCatalogVersion("default", "3.0.0", null, null) } }
            .isInstanceOf(CatalogVersionNotFoundException::class.java)
    }

    @Test
    fun `discarding a draft deletes it, and one holding the tenant's default theme alone is refused`() {
        val tenantId = newTenant()
        released(tenantId)
        runAsApiKey(tenantId) {
            versionTools.createCatalogVersion("default", "2.0.0", null)
            versionTools.createCatalogVersion("default", "3.0.0", null)
            themeTools.createTheme("default", "seasonal", "Seasonal", null, null, null, null, null, catalogVersion = "3.0.0")
        }
        withMediator { SetTenantDefaultTheme(tenantId.key, ThemeKey.of("seasonal"), CatalogKey.of("default")).execute() }

        val left = runAsApiKey(tenantId) { versionTools.deleteCatalogVersion("default", "2.0.0") }

        assertThat(left.map { it.version }).doesNotContain("2.0.0")
        assertThatThrownBy { runAsApiKey(tenantId) { versionTools.deleteCatalogVersion("default", "3.0.0") } }
            .isInstanceOf(CatalogVersionHeldException::class.java)
            .hasMessageContaining("default theme")
    }
}

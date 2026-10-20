// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mcp.tools.ThemeMcpTools
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.TenantRole
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/** A catalog's theme settings over MCP (contract ADR 0007): its default theme, its presets, the house theme. */
class CatalogThemeSettingsMcpToolsIntegrationTest : IntegrationTestBase() {

    @Autowired private lateinit var themeTools: ThemeMcpTools

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

    @Test
    fun `a catalog's default theme and presets are set and read over MCP`() {
        val tenantId = TenantId(createTenant("MCP Theme Settings ${UUID.randomUUID()}").id)

        val settings = runAsApiKey(tenantId) {
            themeTools.createTheme("default", "brand", "Brand", null, null, null, null, null)
            themeTools.setCatalogThemeSettings(
                "default",
                defaultThemeKey = "brand",
                presets = """[{"key":"heading-accent","name":"Heading accent"},{"key":"small-print","name":"Small print"}]""",
            )
        }

        assertThat(settings.defaultTheme).isEqualTo("default/brand")
        assertThat(settings.presets.map { it.key }).containsExactly("heading-accent", "small-print")
        assertThat(settings.availablePresets.map { it.key }).containsExactly("heading-accent", "small-print")
        val cleared = runAsApiKey(tenantId) { themeTools.setCatalogThemeSettings("default", clearDefaultTheme = true) }
        assertThat(cleared.defaultTheme).isNull()
        assertThat(cleared.presets).`as`("left out, the presets stay").hasSize(2)
    }
}

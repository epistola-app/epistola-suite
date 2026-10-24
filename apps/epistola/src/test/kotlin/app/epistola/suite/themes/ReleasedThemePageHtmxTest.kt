// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.default
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate

/**
 * A release's theme opens read-only at its address, `…/catalogs/{namespace}/{key}@{version}/themes/{theme}`
 * (ADR 0033): the release's content, not the draft's, and nothing on the page changes it.
 */
class ReleasedThemePageHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `a released theme opens read-only, as the release holds it`() {
        val tenant = createTenant("Released theme page")
        val catalog = CatalogId.default(TenantId(tenant.id))
        val brand = ThemeId(ThemeKey.of("brand"), catalog)
        withMediator {
            CreateTheme(brand, "Brand as released").execute()
            MarkCatalogReady(catalog).execute()
            ReleaseCatalogDraft(catalog, "1.0.0").execute()
            UpdateTheme(brand, name = "Brand as drafted").execute()
        }

        val page = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/${catalog.address}@1.0.0/themes/brand", String::class.java)

        assertThat(page.statusCode.value()).isEqualTo(200)
        assertThat(page.body.orEmpty()).contains("Brand as released").doesNotContain("Brand as drafted")
        assertThat(page.body.orEmpty()).contains("\"editable\": false").doesNotContain("id=\"delete-theme-form\"")
    }
}

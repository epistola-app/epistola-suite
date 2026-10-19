// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.queries.GetTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Delete on a theme's own page goes through HTMX: refused (the tenant's default theme), the reason
 * shows in its form; done, the browser goes to the catalog's themes.
 */
class ThemeDetailDeleteHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun deleteFromDetail(tenant: String) = restTemplate.postForEntity(
        "/tenants/$tenant/themes/default/brand/delete",
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { add("from", "detail") },
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                set("HX-Request", "true")
            },
        ),
        String::class.java,
    )

    private fun tenantWithBrand(default: Boolean): Pair<String, ThemeId> {
        val tenant = createTenant("Theme Delete")
        val theme = ThemeId(ThemeKey.of("brand"), CatalogId.default(TenantId(tenant.id)))
        withMediator {
            CreateTheme(theme, "Brand").execute()
            if (default) SetTenantDefaultTheme(tenantId = tenant.id, themeId = theme.key, catalogKey = CatalogKey.DEFAULT).execute()
        }
        return tenant.id.value to theme
    }

    @Test
    fun `refused, the reason shows in the detail page's form`() {
        val (tenant, theme) = tenantWithBrand(default = true)

        val response = deleteFromDetail(tenant)

        assertThat(response.statusCode.value()).isEqualTo(422)
        assertThat(response.body).contains("id=\"delete-theme-error\"").contains("default theme")
        assertThat(withMediator { GetTheme(theme).query() }).isNotNull()
    }

    @Test
    fun `done, the browser goes to the catalog's themes`() {
        val (tenant, theme) = tenantWithBrand(default = false)

        val response = deleteFromDetail(tenant)

        assertThat(response.headers.getFirst("HX-Location")).isEqualTo("/tenants/$tenant/catalogs/default/themes")
        assertThat(withMediator { GetTheme(theme).query() }).isNull()
    }
}

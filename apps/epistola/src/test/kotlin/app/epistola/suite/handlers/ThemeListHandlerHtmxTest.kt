package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Locks the themes list endpoint's server-side contract after converting it to the shared
 * data-table (#494). Besides the canonical `HX-Push-Url`, this is the runtime proof that the
 * paged query's `@Nested` row mapping reconstructs the full [app.epistola.suite.themes.Theme]
 * (JSON columns and all) — the rows render with real theme data.
 */
class ThemeListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedThemes(tenantName: String, slugsToNames: List<Pair<String, String>>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        slugsToNames.forEach { (slug, name) ->
            CreateTheme(id = ThemeId(ThemeKey.of(slug), CatalogId.default(tenantId)), name = name).execute()
        }
        tenantId
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `default HTMX list renders the data-table with mapped rows and pushes the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedThemes("Theme Default", listOf("brand-theme" to "Brand Theme", "minimal-theme" to "Minimal Theme")) }

        whenever { getHtmx("/tenants/${tenantId.key}/themes") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"data-table\"")
            // @Nested mapping reconstructed the Theme: its name renders in a row.
            assertThat(response.body).contains("Brand Theme").contains("Minimal Theme")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key}/themes?sort=updated&dir=desc&size=10&page=1")
        }
    }

    @Test
    fun `search filters rows and carries the term into the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedThemes("Theme Search", listOf("brand-theme" to "Brand Theme", "minimal-theme" to "Minimal Theme")) }

        whenever { getHtmx("/tenants/${tenantId.key}/themes?q=Brand") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Brand")
            assertThat(response.body).contains("Brand Theme").doesNotContain("Minimal Theme")
        }
    }

    @Test
    fun `the catalog filter is carried into the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedThemes("Theme Catalog", listOf("brand-theme" to "Brand Theme")) }

        // default catalog key is the tenant key; the filter just needs to round-trip.
        whenever { getHtmx("/tenants/${tenantId.key}/themes?catalog=${tenantId.key}&sort=name&dir=asc") }

        then {
            assertThat(result<ResponseEntity<String>>().headers.getFirst("HX-Push-Url"))
                .contains("catalog=${tenantId.key}").contains("sort=name&dir=asc")
        }
    }
}

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.mediator.execute
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
 * Server-side contract for the fonts list after adding a search box (#494). Fonts stay a card
 * grid (no sort/pagination), so the contract is just: the `q` search filters the families, the
 * canonical URL is pushed, and a full-page load rehydrates the search box on refresh.
 */
class FontListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedFonts(tenantName: String, names: List<Pair<String, String>>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        names.forEach { (slug, name) ->
            ImportFont(
                tenantId = tenantId,
                catalogKey = CatalogKey.of("default"),
                slug = slug,
                name = name,
                kind = "sans",
            ).execute()
        }
        tenantId
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    private fun postHtmx(url: String, currentUrl: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            set("HX-Request", "true")
            set("HX-Current-URL", currentUrl)
        }
        return restTemplate.exchange(url, HttpMethod.POST, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `default HTMX list renders the grid and pushes the clean url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedFonts("Font Default", listOf("roboto" to "Roboto")) }

        whenever { getHtmx("/tenants/${tenantId.key.value}/fonts") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Roboto").contains("asset-grid")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key.value}/fonts")
        }
    }

    @Test
    fun `the search term narrows the families and is carried in the pushed url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedFonts("Font Search", listOf("roboto" to "Roboto", "lato" to "Lato")) }

        whenever { getHtmx("/tenants/${tenantId.key.value}/fonts?q=Robo") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.body).contains("Roboto").doesNotContain("Lato")
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Robo")
        }
    }

    @Test
    fun `a search miss shows the search-aware empty state, not the grid`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedFonts("Font Empty", listOf("roboto" to "Roboto")) }

        whenever { getHtmx("/tenants/${tenantId.key.value}/fonts?q=zzzzz") }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("No fonts match your search").doesNotContain("asset-grid")
            assertThat(body).doesNotContain("No fonts yet")
        }
    }

    @Test
    fun `a full-page load reflects the active search term in the search box`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedFonts("Font Reflect", listOf("roboto" to "Roboto")) }

        whenever { restTemplate.getForEntity("/tenants/${tenantId.key.value}/fonts?q=Robo", String::class.java) }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).containsPattern("search-input[^>]*value=\"Robo\"")
        }
    }

    @Test
    fun `deleting a font re-renders the grid honoring the search active in HX-Current-URL`() = fixture {
        lateinit var tenantId: TenantId
        given {
            tenantId = seedFonts(
                "Font Del Search",
                listOf("roboto" to "Roboto", "lato" to "Lato", "temp" to "Temp Font"),
            )
        }

        // The delete POSTs to a fixed URL; the active search rides only in HX-Current-URL.
        whenever {
            postHtmx(
                "/tenants/${tenantId.key.value}/fonts/default/temp/delete",
                "http://localhost/tenants/${tenantId.key.value}/fonts?q=Robo",
            )
        }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("Roboto") // matches the active q
            assertThat(body).doesNotContain("Lato") // filtered out, though it still exists
            assertThat(body).doesNotContain("Temp Font") // the deleted family
        }
    }
}

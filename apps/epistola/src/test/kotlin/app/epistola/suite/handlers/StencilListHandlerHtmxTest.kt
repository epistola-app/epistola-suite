package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
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
 * Locks the stencils list endpoint's server-side contract after converting it to the shared
 * data-table (#494). Stencils carry @Json tags, so this also proves the paged query's
 * `@Nested` row mapping reconstructs the full [app.epistola.suite.stencils.Stencil].
 */
class StencilListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedStencils(tenantName: String, slugsToNames: List<Pair<String, String>>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        slugsToNames.forEach { (slug, name) ->
            CreateStencil(id = StencilId(StencilKey.of(slug), CatalogId.default(tenantId)), name = name).execute()
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
        given { tenantId = seedStencils("Stencil Default", listOf("header-block" to "Header Block", "footer-block" to "Footer Block")) }

        whenever { getHtmx("/tenants/${tenantId.key}/stencils") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"data-table\"")
            assertThat(response.body).contains("Header Block").contains("Footer Block")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key}/stencils?sort=updated&dir=desc&size=10&page=1")
        }
    }

    @Test
    fun `search filters rows and carries the term into the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedStencils("Stencil Search", listOf("header-block" to "Header Block", "footer-block" to "Footer Block")) }

        whenever { getHtmx("/tenants/${tenantId.key}/stencils?q=Header") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Header")
            assertThat(response.body).contains("Header Block").doesNotContain("Footer Block")
        }
    }
}

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
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
 * Locks the attributes list endpoint's server-side contract after converting it to the
 * shared data-table (#494). Attributes carry @Json allowed_values, so this also proves the
 * paged query's `@Nested` row mapping reconstructs the full definition. Search is newly added.
 */
class AttributeListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedAttributes(tenantName: String, slugsToNames: List<Pair<String, String>>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        slugsToNames.forEach { (slug, name) ->
            CreateAttributeDefinition(id = AttributeId(AttributeKey.of(slug), CatalogId.default(tenantId)), displayName = name).execute()
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
        given { tenantId = seedAttributes("Attr Default", listOf("language" to "Language", "brand" to "Brand")) }

        whenever { getHtmx("/tenants/${tenantId.key}/attributes") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"data-table\"")
            assertThat(response.body).contains("Language").contains("Brand")
            // Default sort is display name ASC.
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key}/attributes?sort=name&dir=asc&size=10&page=1")
        }
    }

    @Test
    fun `search filters rows and carries the term into the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedAttributes("Attr Search", listOf("language" to "Language", "brand" to "Brand")) }

        whenever { getHtmx("/tenants/${tenantId.key}/attributes?q=Lang") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Lang")
            assertThat(response.body).contains("Language").doesNotContain("Brand")
        }
    }
}

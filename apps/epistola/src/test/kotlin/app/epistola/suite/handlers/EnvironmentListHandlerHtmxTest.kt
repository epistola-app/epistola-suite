package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
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
 * Locks the environments list endpoint's server-side contract after converting it to the
 * shared data-table (#494). Environments is the no-catalog-filter case: it proves the
 * generalized [app.epistola.suite.htmx.table.ListViewState] filters map works with only a
 * search filter and that the canonical `HX-Push-Url` omits filters when absent.
 */
class EnvironmentListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedEnvironments(tenantName: String, ids: List<Pair<String, String>>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        ids.forEach { (slug, name) ->
            CreateEnvironment(id = EnvironmentId(EnvironmentKey.of(slug), tenantId), name = name).execute()
        }
        tenantId
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `default HTMX list renders the data-table and pushes the canonical url without filters`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedEnvironments("Env Default", listOf("staging" to "Staging", "prod" to "Production")) }

        whenever { getHtmx("/tenants/${tenantId.key}/environments") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"data-table\"")
            // Default sort is name ASC; no q filter present, so it is omitted.
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key}/environments?sort=name&dir=asc&size=10&page=1")
        }
    }

    @Test
    fun `search filters rows and carries the term into the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedEnvironments("Env Search", listOf("staging" to "Staging", "prod" to "Production")) }

        whenever { getHtmx("/tenants/${tenantId.key}/environments?q=Stag") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Stag")
            assertThat(response.body).contains("Staging").doesNotContain("Production")
        }
    }

    @Test
    fun `sorting by a whitelisted column is preserved in the pushed url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedEnvironments("Env Sort", listOf("staging" to "Staging", "prod" to "Production")) }

        whenever { getHtmx("/tenants/${tenantId.key}/environments?sort=created&dir=desc") }

        then {
            assertThat(result<ResponseEntity<String>>().headers.getFirst("HX-Push-Url"))
                .contains("sort=created&dir=desc")
        }
    }
}

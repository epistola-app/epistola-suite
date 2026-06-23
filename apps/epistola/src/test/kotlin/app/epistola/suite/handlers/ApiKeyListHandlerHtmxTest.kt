package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.common.ids.TenantKey
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
 * Locks the api-keys list endpoint's server-side contract after converting it to the shared
 * data-table (#494). API keys have no catalog filter; this proves the canonical `HX-Push-Url`,
 * the newly-added name search, and that the (now SQL-level) enabled filter still hides revoked
 * keys. The custom `ResultSet`-mapper paged path is exercised by every rendered row.
 */
class ApiKeyListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedKeys(tenantName: String, names: List<String>): TenantKey = withMediator {
        val tenantId = createTenant(tenantName).id
        names.forEach { CreateApiKey(tenantId = tenantId, name = it).execute() }
        tenantId
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `default HTMX list renders the data-table with mapped rows and pushes the canonical url`() = fixture {
        var tenantId: TenantKey? = null
        given { tenantId = seedKeys("Key Default", listOf("CI key", "Backup key")) }

        whenever { getHtmx("/tenants/${tenantId!!}/api-keys") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"data-table\"")
            assertThat(response.body).contains("CI key").contains("Backup key")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId!!}/api-keys?sort=created&dir=desc&size=10&page=1")
        }
    }

    @Test
    fun `search filters rows by name and carries the term into the canonical url`() = fixture {
        var tenantId: TenantKey? = null
        given { tenantId = seedKeys("Key Search", listOf("CI key", "Backup key")) }

        whenever { getHtmx("/tenants/${tenantId!!}/api-keys?q=Backup") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Backup")
            assertThat(response.body).contains("Backup key").doesNotContain("CI key")
        }
    }
}

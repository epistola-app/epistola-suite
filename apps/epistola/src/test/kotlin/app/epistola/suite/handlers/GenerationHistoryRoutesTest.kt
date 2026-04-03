package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class GenerationHistoryRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET generation-history returns dashboard page`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Dashboard Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/generation-history",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Generation History")
            assertThat(response.body).contains("Total Generated")
            assertThat(response.body).contains("In Queue")
            assertThat(response.body).contains("Completed")
            assertThat(response.body).contains("Failed")
            assertThat(response.body).contains("Most Used Templates")
            assertThat(response.body).contains("Recent Jobs")
        }
    }

    @Test
    fun `GET generation-history shows empty state when no data`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Empty Dashboard Tenant")
        }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${tenant.id}/generation-history",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("No template usage data")
            assertThat(response.body).contains("No generation jobs yet")
        }
    }

    @Test
    fun `GET generation-history search returns HTMX fragment`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("HTMX Search Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${tenant.id}/generation-history/search?status=COMPLETED",
                HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // HTMX fragment response should be a table fragment, not a full page
            assertThat(response.body).doesNotContain("Generation History")
        }
    }
}

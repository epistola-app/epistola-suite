package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TenantRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `GET homepage returns tenant list page`() = fixture {
        given {
            tenant("Acme Corp")
            tenant("Globex Inc")
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Tenants")
            assertThat(response.body).contains("Acme Corp")
            assertThat(response.body).contains("Globex Inc")
        }
    }

    @Test
    fun `GET homepage returns empty table when no tenants exist`() = fixture {
        given {
            noTenants()
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Tenants")
            assertThat(response.body).contains("No tenants yet")
        }
    }

    @Test
    fun `GET tenants search filters by name`() = fixture {
        given {
            tenant("Acme Corp")
            tenant("Globex Inc")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/search?q=Acme",
                org.springframework.http.HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Acme Corp")
            assertThat(response.body).doesNotContain("Globex Inc")
        }
    }

    @Test
    fun `POST tenants creates new tenant`() = fixture {
        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "new-tenant")
            formData.add("name", "New Tenant")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("New Tenant")

            // Clean up tenant created via HTTP (not tracked by fixture)
            val createdTenant = mediator.query(ListTenants("New Tenant")).first()
            mediator.send(DeleteTenant(createdTenant.id))
        }
    }

    @Test
    fun `tenant row links to tenant home page`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
        }

        whenever {
            restTemplate.getForEntity("/", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("/tenants/${tenant.id}")
        }
    }
}

package app.epistola.suite.tenants

import app.epistola.suite.TestcontainersConfiguration
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.CreateTenantHandler
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class TenantRoutesTest {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var createTenantHandler: CreateTenantHandler

    @BeforeEach
    fun setUp() {
        jdbi.useHandle<Exception> { handle ->
            handle.execute("DELETE FROM document_templates")
            handle.execute("DELETE FROM tenants")
        }
    }

    @Test
    fun `GET homepage returns tenant list page`() {
        createTenantHandler.handle(CreateTenant("Acme Corp"))
        createTenantHandler.handle(CreateTenant("Globex Inc"))

        val response = restTemplate.getForEntity("/", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Tenants")
        assertThat(response.body).contains("Acme Corp")
        assertThat(response.body).contains("Globex Inc")
    }

    @Test
    fun `GET homepage returns empty table when no tenants exist`() {
        val response = restTemplate.getForEntity("/", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Tenants")
        assertThat(response.body).contains("No tenants yet")
    }

    @Test
    fun `GET tenants search filters by name`() {
        createTenantHandler.handle(CreateTenant("Acme Corp"))
        createTenantHandler.handle(CreateTenant("Globex Inc"))

        val headers = HttpHeaders()
        headers.set("HX-Request", "true")

        val request = HttpEntity<Void>(headers)
        val response = restTemplate.exchange(
            "/tenants/search?q=Acme",
            org.springframework.http.HttpMethod.GET,
            request,
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Acme Corp")
        assertThat(response.body).doesNotContain("Globex Inc")
    }

    @Test
    fun `POST tenants creates new tenant`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        headers.set("HX-Request", "true")

        val formData = LinkedMultiValueMap<String, String>()
        formData.add("name", "New Tenant")

        val request = HttpEntity(formData, headers)
        val response = restTemplate.postForEntity("/tenants", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("New Tenant")
    }

    @Test
    fun `tenant row links to templates page`() {
        val tenant = createTenantHandler.handle(CreateTenant("Test Tenant"))

        val response = restTemplate.getForEntity("/", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("/tenants/${tenant.id}/templates")
    }
}

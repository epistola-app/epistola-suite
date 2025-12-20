package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplateHandler
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
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
class DocumentTemplateRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var createDocumentTemplateHandler: CreateDocumentTemplateHandler

    @Autowired
    private lateinit var listDocumentTemplatesHandler: ListDocumentTemplatesHandler

    private lateinit var testTenant: Tenant

    @BeforeEach
    fun setUp() {
        testTenant = createTenant("Test Tenant")
    }

    private fun createSampleTemplates() {
        createDocumentTemplateHandler.handle(CreateDocumentTemplate(tenantId = testTenant.id, name = "Invoice Template"))
        createDocumentTemplateHandler.handle(CreateDocumentTemplate(tenantId = testTenant.id, name = "Contract Template"))
        createDocumentTemplateHandler.handle(CreateDocumentTemplate(tenantId = testTenant.id, name = "Letter Template"))
    }

    @Test
    fun `GET templates returns list page with template data`() {
        createSampleTemplates()
        val response = restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Document Templates")
        assertThat(response.body).contains("Invoice Template")
        assertThat(response.body).contains("Contract Template")
        assertThat(response.body).contains("Letter Template")
    }

    @Test
    fun `GET templates returns empty table when no templates exist`() {
        val response = restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Document Templates")
        assertThat(response.body).doesNotContain("Invoice Template")
    }

    @Test
    fun `GET templates search filters by name`() {
        createSampleTemplates()
        val headers = HttpHeaders()
        headers.set("HX-Request", "true")

        val request = HttpEntity<Void>(headers)
        val response = restTemplate.exchange(
            "/tenants/${testTenant.id}/templates/search?q=Invoice",
            org.springframework.http.HttpMethod.GET,
            request,
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Invoice Template")
        assertThat(response.body).doesNotContain("Contract Template")
        assertThat(response.body).doesNotContain("Letter Template")
    }

    @Test
    fun `GET templates search with empty query returns all templates`() {
        createSampleTemplates()
        val headers = HttpHeaders()
        headers.set("HX-Request", "true")

        val request = HttpEntity<Void>(headers)
        val response = restTemplate.exchange(
            "/tenants/${testTenant.id}/templates/search",
            org.springframework.http.HttpMethod.GET,
            request,
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Invoice Template")
        assertThat(response.body).contains("Contract Template")
        assertThat(response.body).contains("Letter Template")
    }

    @Test
    fun `POST templates creates new template and redirects`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

        val formData = LinkedMultiValueMap<String, String>()
        formData.add("name", "New Template")

        val request = HttpEntity(formData, headers)
        val response = restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("New Template")

        val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = testTenant.id, searchTerm = "New Template"))
        assertThat(templates).hasSize(1)
    }

    @Test
    fun `POST templates with HTMX returns table rows fragment`() {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
        headers.set("HX-Request", "true")

        val formData = LinkedMultiValueMap<String, String>()
        formData.add("name", "HTMX Template")

        val request = HttpEntity(formData, headers)
        val response = restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("HTMX Template")
        // Fragment should not contain full page structure
        assertThat(response.body).doesNotContain("<!DOCTYPE html>")
        assertThat(response.body).doesNotContain("<head>")

        val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = testTenant.id, searchTerm = "HTMX Template"))
        assertThat(templates).hasSize(1)
    }
}

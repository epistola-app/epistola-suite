package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import app.epistola.suite.tenants.Tenant
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
class DocumentTemplateRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var listDocumentTemplatesHandler: ListDocumentTemplatesHandler

    @Test
    fun `GET templates returns list page with template data`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Invoice Template")
            template(testTenant, "Contract Template")
            template(testTenant, "Letter Template")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Document Templates")
            assertThat(response.body).contains("Invoice Template")
            assertThat(response.body).contains("Contract Template")
            assertThat(response.body).contains("Letter Template")
        }
    }

    @Test
    fun `GET templates returns empty table when no templates exist`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            restTemplate.getForEntity("/tenants/${testTenant.id}/templates", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Document Templates")
            assertThat(response.body).doesNotContain("Invoice Template")
        }
    }

    @Test
    fun `GET templates search filters by name`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Invoice Template")
            template(testTenant, "Contract Template")
            template(testTenant, "Letter Template")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/search?q=Invoice",
                org.springframework.http.HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Invoice Template")
            assertThat(response.body).doesNotContain("Contract Template")
            assertThat(response.body).doesNotContain("Letter Template")
        }
    }

    @Test
    fun `GET templates search with empty query returns all templates`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
            template(testTenant, "Invoice Template")
            template(testTenant, "Contract Template")
            template(testTenant, "Letter Template")
        }

        whenever {
            val headers = HttpHeaders()
            headers.set("HX-Request", "true")
            val request = HttpEntity<Void>(headers)
            restTemplate.exchange(
                "/tenants/${testTenant.id}/templates/search",
                org.springframework.http.HttpMethod.GET,
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Invoice Template")
            assertThat(response.body).contains("Contract Template")
            assertThat(response.body).contains("Letter Template")
        }
    }

    @Test
    fun `POST templates creates new template`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("name", "New Template")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("New Template")

            val templates = listDocumentTemplatesHandler.handle(
                ListDocumentTemplates(tenantId = testTenant.id, searchTerm = "New Template"),
            )
            assertThat(templates).hasSize(1)
        }
    }

    @Test
    fun `POST templates with HTMX returns table rows fragment`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("name", "HTMX Template")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("HTMX Template")
            assertThat(response.body).doesNotContain("<!DOCTYPE html>")
            assertThat(response.body).doesNotContain("<head>")

            val templates = listDocumentTemplatesHandler.handle(
                ListDocumentTemplates(tenantId = testTenant.id, searchTerm = "HTMX Template"),
            )
            assertThat(templates).hasSize(1)
        }
    }

    @Test
    fun `POST templates with empty name returns validation error`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("name", "")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Name is required")
            assertThat(response.body).contains("form-error")

            val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = testTenant.id))
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `POST templates with blank name returns validation error`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("name", "   ")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Name is required")

            val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = testTenant.id))
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `POST templates with name exceeding 255 characters returns validation error`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("name", "a".repeat(256))
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Name must be 255 characters or less")

            val templates = listDocumentTemplatesHandler.handle(ListDocumentTemplates(tenantId = testTenant.id))
            assertThat(templates).isEmpty()
        }
    }

    @Test
    fun `POST templates validation error preserves form value`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Test Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("name", "a".repeat(256))
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants/${testTenant.id}/templates", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The form should contain the submitted value (trimmed but preserved)
            assertThat(response.body).contains("value=\"${"a".repeat(256)}\"")
        }
    }
}

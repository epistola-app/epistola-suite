package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.templates.queries.ListDocumentTemplatesHandler
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import tools.jackson.databind.ObjectMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class DocumentTemplateRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var listDocumentTemplatesHandler: ListDocumentTemplatesHandler

    @Autowired
    private lateinit var objectMapper: ObjectMapper

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
            formData.add("slug", "new-template")
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
            formData.add("slug", "htmx-template")
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
            formData.add("slug", "valid-slug")
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
            formData.add("slug", "valid-slug")
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
            formData.add("slug", "valid-slug")
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

    @Nested
    inner class ValidateSchemaEndpointTest {

        @Test
        fun `POST validate-schema returns compatible when no examples exist`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"schema": {"type": "object", "properties": {"name": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":true")
                assertThat(response.body).contains("\"migrations\":[]")
                assertThat(response.body).contains("\"errors\":[]")
            }
        }

        @Test
        fun `POST validate-schema returns compatible when examples match schema`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add a data example to the template
                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"name": {"type": "string"}}}""",
                )
                val dataExamples = listOf(
                    mapOf(
                        "id" to "1",
                        "name" to "Example 1",
                        "data" to mapOf("name" to "John"),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataModel = objectMapper.valueToTree(dataModel),
                        dataExamples = dataExamples.map {
                            app.epistola.suite.templates.model.DataExample(
                                id = it["id"] as String,
                                name = it["name"] as String,
                                data = objectMapper.valueToTree(it["data"]),
                            )
                        },
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"schema": {"type": "object", "properties": {"name": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":true")
            }
        }

        @Test
        fun `POST validate-schema returns migrations for type mismatch`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add a data example with a number where string is expected
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "1",
                        name = "Example 1",
                        data = objectMapper.valueToTree(mapOf("count" to 42)),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Schema expects string but example has number
                val body = """{"schema": {"type": "object", "properties": {"count": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":false")
                assertThat(response.body).contains("\"autoMigratable\":true")
                assertThat(response.body).contains("\"exampleId\":\"1\"")
            }
        }

        @Test
        fun `POST validate-schema returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"schema": {"type": "object", "properties": {"name": {"type": "string"}}}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/$nonExistentTemplateId/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `POST validate-schema uses provided examples instead of stored ones`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add a stored data example that matches the schema
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "stored",
                        name = "Stored Example",
                        data = objectMapper.valueToTree(mapOf("name" to "John")),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Provide different examples in the request that have type mismatch
                val body = """{
                    "schema": {"type": "object", "properties": {"name": {"type": "string"}}},
                    "examples": [{"id": "provided", "name": "Provided Example", "data": {"name": 123}}]
                }"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/validate-schema",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"compatible\":false")
                assertThat(response.body).contains("\"exampleId\":\"provided\"")
                assertThat(response.body).doesNotContain("\"exampleId\":\"stored\"")
            }
        }
    }

    @Nested
    inner class DataExampleEndpointsTest {

        @Test
        fun `PATCH data-example updates a single example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add initial data examples
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-1",
                        name = "Example 1",
                        data = objectMapper.valueToTree(mapOf("name" to "John")),
                    ),
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-2",
                        name = "Example 2",
                        data = objectMapper.valueToTree(mapOf("name" to "Jane")),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"name": "Updated Example", "data": {"name": "Updated John"}}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"id\":\"example-1\"")
                assertThat(response.body).contains("\"name\":\"Updated Example\"")
                assertThat(response.body).contains("Updated John")

                // Verify the other example is unchanged
                val updated = mediator.query(GetDocumentTemplate(tenantId = testTenant.id, id = template.id))
                assertThat(updated).isNotNull
                assertThat(updated!!.dataExamples).hasSize(2)
                assertThat(updated.dataExamples.find { it.id == "example-2" }?.name).isEqualTo("Example 2")
            }
        }

        @Test
        fun `PATCH data-example with partial update only updates provided fields`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-1",
                        name = "Original Name",
                        data = objectMapper.valueToTree(mapOf("field" to "original")),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Only update name, not data
                val body = """{"name": "New Name"}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("\"name\":\"New Name\"")
                assertThat(response.body).contains("original") // Data unchanged
            }
        }

        @Test
        fun `PATCH data-example returns 404 for non-existent example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"name": "Updated"}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/${template.id}/data-examples/non-existent",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `PATCH data-example returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"name": "Updated"}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/$nonExistentTemplateId/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `PATCH data-example validates against schema`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Add schema and example
                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"count": {"type": "integer"}}}""",
                )
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-1",
                        name = "Example 1",
                        data = objectMapper.valueToTree(mapOf("count" to 42)),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataModel = objectMapper.valueToTree(dataModel),
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Invalid: count should be integer but sending string
                val body = """{"data": {"count": "not-a-number"}}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(response.body).contains("errors")
            }
        }

        @Test
        fun `PATCH data-example with forceUpdate saves despite validation errors`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"count": {"type": "integer"}}}""",
                )
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-1",
                        name = "Example 1",
                        data = objectMapper.valueToTree(mapOf("count" to 42)),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataModel = objectMapper.valueToTree(dataModel),
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"data": {"count": "not-a-number"}, "forceUpdate": true}"""
                val request = HttpEntity(body, headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/${template.id}/data-examples/example-1",
                    HttpMethod.PATCH,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("warnings")
                assertThat(response.body).contains("not-a-number")
            }
        }

        @Test
        fun `DELETE data-example removes a single example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-1",
                        name = "Example 1",
                        data = objectMapper.valueToTree(mapOf("name" to "John")),
                    ),
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-2",
                        name = "Example 2",
                        data = objectMapper.valueToTree(mapOf("name" to "Jane")),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/${template.id}/data-examples/example-1",
                    HttpMethod.DELETE,
                    null,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)

                // Verify example-1 is gone, example-2 remains
                val updated = mediator.query(GetDocumentTemplate(tenantId = testTenant.id, id = template.id))
                assertThat(updated).isNotNull
                assertThat(updated!!.dataExamples).hasSize(1)
                assertThat(updated.dataExamples[0].id).isEqualTo("example-2")
            }
        }

        @Test
        fun `DELETE data-example returns 404 for non-existent example`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val dataExamples = listOf(
                    app.epistola.suite.templates.model.DataExample(
                        id = "example-1",
                        name = "Example 1",
                        data = objectMapper.valueToTree(mapOf("name" to "John")),
                    ),
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataExamples = dataExamples,
                    ),
                )
            }

            whenever {
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/${template.id}/data-examples/non-existent",
                    HttpMethod.DELETE,
                    null,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `DELETE data-example returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/templates/$nonExistentTemplateId/data-examples/example-1",
                    HttpMethod.DELETE,
                    null,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }
    }

    @Nested
    inner class PreviewEndpointTest {

        @Test
        fun `POST preview returns 400 with structured errors when data validation fails`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantId? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                // Create a variant (which also creates a draft)
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // Add schema that requires 'name' field
                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataModel = objectMapper.valueToTree(dataModel),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Send data without required 'name' field
                val body = """{"data": {}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/variants/$variantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_JSON)).isTrue()
                assertThat(response.body).contains("errors")
                assertThat(response.body).contains("name")
            }
        }

        @Test
        fun `POST preview returns 400 with structured errors when data type is wrong`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantId? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // Add schema that expects 'count' to be integer
                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"count": {"type": "integer"}}}""",
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataModel = objectMapper.valueToTree(dataModel),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Send string instead of integer
                val body = """{"data": {"count": "not-a-number"}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/variants/$variantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
                assertThat(response.headers.contentType?.includes(MediaType.APPLICATION_JSON)).isTrue()
                assertThat(response.body).contains("errors")
            }
        }

        @Test
        fun `POST preview returns 404 for non-existent variant`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            val nonExistentVariantId = "non-existent-variant"

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"data": {}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/variants/$nonExistentVariantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `POST preview returns 404 for non-existent template`() = fixture {
            lateinit var testTenant: Tenant
            val nonExistentTemplateId = "non-existent-template"
            val nonExistentVariantId = "non-existent-variant"

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val body = """{"data": {}}"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/$nonExistentTemplateId/variants/$nonExistentVariantId/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
            }
        }

        @Test
        fun `POST preview returns PDF when data is valid`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantId? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // Add schema that requires 'name' field
                val dataModel = objectMapper.readTree(
                    """{"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}""",
                )
                mediator.send(
                    UpdateDocumentTemplate(
                        tenantId = testTenant.id,
                        id = template.id,
                        dataModel = objectMapper.valueToTree(dataModel),
                    ),
                )
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Send valid data with required 'name' field and a minimal template model
                // The templateModel is required because the draft is created without one
                val body = """{
                    "data": {"name": "John Doe"},
                    "templateModel": {
                        "modelVersion": 1,
                        "root": "root-1",
                        "nodes": {
                            "root-1": {"id": "root-1", "type": "root", "slots": ["slot-1"]}
                        },
                        "slots": {
                            "slot-1": {"id": "slot-1", "nodeId": "root-1", "name": "children", "children": []}
                        },
                        "themeRef": {"type": "inherit"}
                    }
                }"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/variants/$variantId/preview",
                    request,
                    ByteArray::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<ByteArray>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
            }
        }

        @Test
        fun `POST preview returns PDF when no schema is defined`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var template: DocumentTemplate
            var variantId: VariantId? = null

            given {
                testTenant = tenant("Test Tenant")
                template = template(testTenant, "Test Template")
                val createdVariant = variant(testTenant, template, "Default")
                variantId = createdVariant.id
                // No schema defined, any data should be valid
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                // Provide a minimal v2 template document since drafts are created without one
                val body = """{
                    "data": {"anything": "goes"},
                    "templateModel": {
                        "modelVersion": 1,
                        "root": "root-1",
                        "nodes": {
                            "root-1": {"id": "root-1", "type": "root", "slots": ["slot-1"]}
                        },
                        "slots": {
                            "slot-1": {"id": "slot-1", "nodeId": "root-1", "name": "children", "children": []}
                        },
                        "themeRef": {"type": "inherit"}
                    }
                }"""
                val request = HttpEntity(body, headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/templates/${template.id}/variants/$variantId/preview",
                    request,
                    ByteArray::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<ByteArray>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
            }
        }
    }
}

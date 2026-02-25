package app.epistola.suite.loadtest

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.model.DataExample
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
import tools.jackson.databind.ObjectMapper

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class LoadTestHandlerTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Nested
    inner class NewForm {
        @Test
        fun `GET new returns form with environment dropdown`() = fixture {
            lateinit var testTenant: Tenant

            given {
                testTenant = tenant("Test Tenant")
                val envId = TestIdHelpers.nextEnvironmentId()
                CreateEnvironment(id = envId, tenantId = testTenant.id, name = "Production").execute()
            }

            whenever {
                restTemplate.getForEntity(
                    "/tenants/${testTenant.id}/load-tests/new",
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("New Load Test")
                assertThat(response.body).contains("Production")
                assertThat(response.body).contains("Select a template")
            }
        }
    }

    @Nested
    inner class TemplateOptions {
        @Test
        fun `GET template-options without templateId returns empty-state fragment`() = fixture {
            lateinit var testTenant: Tenant

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/template-options",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("Select a template first")
            }
        }

        @Test
        fun `GET template-options with invalid templateId returns empty-state fragment`() = fixture {
            lateinit var testTenant: Tenant

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/template-options?templateId=",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("Select a template first")
            }
        }

        @Test
        fun `GET template-options with valid templateId returns variant dropdown`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value
                // The default variant is created automatically with the template
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                headers.set("HX-Trigger-Name", "templateId")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/template-options?templateId=$templateId",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                // Should contain variant dropdown
                assertThat(response.body).contains("variantId")
                // Should contain default variant as selected
                assertThat(response.body).contains("default")
            }
        }

        @Test
        fun `GET template-options with templateId and exampleId returns pre-filled JSON`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var exampleId: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value

                // Add data examples to the template
                val exampleData = objectMapper.createObjectNode().put("title", "Test Invoice")
                val example = DataExample(id = "example-1", name = "Sample Invoice", data = exampleData)
                exampleId = example.id
                UpdateDocumentTemplate(
                    tenantId = testTenant.id,
                    id = template.id,
                    dataExamples = listOf(example),
                ).execute()
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                headers.set("HX-Trigger-Name", "exampleId")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/template-options" +
                        "?templateId=$templateId&exampleId=$exampleId&variantId=default",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                // Should contain the pre-filled JSON from the example
                assertThat(response.body).contains("Test Invoice")
                // Should contain the example dropdown with selection
                assertThat(response.body).contains("Sample Invoice")
                // Variant should be preserved
                assertThat(response.body).contains("variantId")
            }
        }

        @Test
        fun `GET template-options with template without examples shows manual JSON entry`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Empty Template")
                templateId = template.id.value
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                headers.set("HX-Trigger-Name", "templateId")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/template-options?templateId=$templateId",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                // Should contain manual entry hint
                assertThat(response.body).contains("Enter JSON test data manually")
                // Should NOT contain example selector
                assertThat(response.body).doesNotContain("Data Example")
            }
        }
    }
}

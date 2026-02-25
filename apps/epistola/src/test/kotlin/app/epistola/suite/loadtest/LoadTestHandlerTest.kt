package app.epistola.suite.loadtest

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.CreateVersion
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
        fun `GET new returns form with template dropdown`() = fixture {
            lateinit var testTenant: Tenant

            given {
                testTenant = tenant("Test Tenant")
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
                assertThat(response.body).contains("Select a template")
                // Empty state: disabled dropdowns
                assertThat(response.body).contains("Select a template first")
            }
        }
    }

    @Nested
    inner class HtmxTemplateOptions {
        @Test
        fun `GET new (HTMX) without templateId returns empty-state fragment`() = fixture {
            lateinit var testTenant: Tenant

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/new",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("Select a template first")
                assertThat(response.body).contains("Select a variant first")
            }
        }

        @Test
        fun `GET new (HTMX) with invalid templateId returns empty-state fragment`() = fixture {
            lateinit var testTenant: Tenant

            given {
                testTenant = tenant("Test Tenant")
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/new?templateId=",
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
        fun `GET new (HTMX) with valid templateId returns variant, version and environment dropdowns`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value
                CreateVersion(
                    tenantId = testTenant.id,
                    templateId = template.id,
                    variantId = VariantId.of("${template.id}-default"),
                ).execute()
                val envId = TestIdHelpers.nextEnvironmentId()
                CreateEnvironment(id = envId, tenantId = testTenant.id, name = "Production").execute()
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                headers.set("HX-Trigger-Name", "templateId")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/new?templateId=$templateId",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                // Should contain variant dropdown with default selected
                assertThat(response.body).contains("variantId")
                assertThat(response.body).contains("default")
                // Should contain version dropdown with the draft version
                assertThat(response.body).contains("versionId")
                assertThat(response.body).contains("DRAFT")
                // Should contain environment dropdown (no version selected yet)
                assertThat(response.body).contains("environmentId")
                assertThat(response.body).contains("Production")
            }
        }

        @Test
        fun `GET new (HTMX) selecting a version hides environment dropdown`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value
                val defaultVariantId = VariantId.of("${template.id}-default")
                CreateVersion(
                    tenantId = testTenant.id,
                    templateId = template.id,
                    variantId = defaultVariantId,
                ).execute()
                val envId = TestIdHelpers.nextEnvironmentId()
                CreateEnvironment(id = envId, tenantId = testTenant.id, name = "Production").execute()
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                headers.set("HX-Trigger-Name", "versionId")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/new" +
                        "?templateId=$templateId&variantId=$templateId-default&versionId=1",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                // Version dropdown should be present with selection
                assertThat(response.body).contains("versionId")
                // Environment dropdown should NOT be present (version was selected)
                assertThat(response.body).doesNotContain("environmentId")
            }
        }

        @Test
        fun `GET new (HTMX) with variant change loads versions for that variant`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var customVariantId: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value
                val customVariant = variant(testTenant, template, title = "Dutch")
                customVariantId = customVariant.id.value
                // Create a draft version for the custom variant
                CreateVersion(
                    tenantId = testTenant.id,
                    templateId = template.id,
                    variantId = customVariant.id,
                ).execute()
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                headers.set("HX-Trigger-Name", "variantId")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/load-tests/new" +
                        "?templateId=$templateId&variantId=$customVariantId",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                // Should contain Dutch variant selected
                assertThat(response.body).contains("Dutch")
                // Should contain version for this variant
                assertThat(response.body).contains("versionId")
                assertThat(response.body).contains("DRAFT")
            }
        }

        @Test
        fun `GET new (HTMX) with templateId and exampleId returns pre-filled JSON`() = fixture {
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
                    "/tenants/${testTenant.id}/load-tests/new" +
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
        fun `GET new (HTMX) with template without examples shows manual JSON entry`() = fixture {
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
                    "/tenants/${testTenant.id}/load-tests/new?templateId=$templateId",
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
                // Should still have version dropdown (even if empty)
                assertThat(response.body).contains("versionId")
            }
        }
    }
}

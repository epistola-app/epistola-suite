package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.TestIdHelpers
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

@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class VersionComparisonRoutesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Nested
    inner class CompareDialog {

        @Test
        fun `GET compare returns dialog fragment with version selectors`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var variantKey: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                val tenantId = TenantId(testTenant.id)
                val tplId = TemplateId(template.id, tenantId)
                templateId = template.id.value
                variantKey = "${template.id}-default"
                CreateVersion(
                    variantId = VariantId(VariantKey.of(variantKey), tplId),
                ).execute()
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/catalogs/default/templates/$templateId/variants/$variantKey/compare",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("compare-version-a")
                assertThat(response.body).contains("compare-version-b")
                assertThat(response.body).contains("Compare")
            }
        }

        @Test
        fun `GET compare shows version selectors for variant with versions`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var variantKey: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value
                // variant() auto-creates a draft, so versions will be available
                val variant = variant(testTenant, template, "English")
                variantKey = variant.id.value
            }

            whenever {
                val headers = HttpHeaders()
                headers.set("HX-Request", "true")
                val request = HttpEntity<Void>(headers)
                restTemplate.exchange(
                    "/tenants/${testTenant.id}/catalogs/default/templates/$templateId/variants/$variantKey/compare",
                    HttpMethod.GET,
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.body).contains("compare-version-a")
                assertThat(response.body).contains("(draft)")
            }
        }

        @Test
        fun `GET compare without HTMX redirects to template detail`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var variantKey: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value
                val variant = variant(testTenant, template)
                variantKey = variant.id.value
            }

            whenever {
                restTemplate.getForEntity(
                    "/tenants/${testTenant.id}/catalogs/default/templates/$templateId/variants/$variantKey/compare",
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                // TestRestTemplate follows redirects, so we should get the detail page
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            }
        }
    }

    @Nested
    inner class PreviewVersion {

        @Test
        fun `POST version preview returns PDF for published version`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var variantKey: String
            var versionNumber: Int = 0

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                val tenantId = TenantId(testTenant.id)
                val tplId = TemplateId(template.id, tenantId)
                templateId = template.id.value
                variantKey = "${template.id}-default"
                val varId = VariantId(VariantKey.of(variantKey), tplId)

                // Create a draft version
                val version = CreateVersion(variantId = varId).execute()!!
                versionNumber = version.id.value

                // Publish to an environment to make it published
                val envKey = TestIdHelpers.nextEnvironmentId()
                CreateEnvironment(id = EnvironmentId(envKey, tenantId), name = "Production").execute()
                PublishToEnvironment(
                    versionId = VersionId(VersionKey.of(versionNumber), varId),
                    environmentId = EnvironmentId(envKey, tenantId),
                ).execute()
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val request = HttpEntity("{}", headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/catalogs/default/templates/$templateId/variants/$variantKey/versions/$versionNumber/preview",
                    request,
                    ByteArray::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<ByteArray>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
                assertThat(response.body).isNotEmpty()
            }
        }

        @Test
        fun `POST version preview returns PDF for draft version`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var variantKey: String
            var versionNumber: Int = 0

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                val tenantId = TenantId(testTenant.id)
                val tplId = TemplateId(template.id, tenantId)
                templateId = template.id.value
                variantKey = "${template.id}-default"
                val varId = VariantId(VariantKey.of(variantKey), tplId)

                val version = CreateVersion(variantId = varId).execute()!!
                versionNumber = version.id.value
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val request = HttpEntity("{}", headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/catalogs/default/templates/$templateId/variants/$variantKey/versions/$versionNumber/preview",
                    request,
                    ByteArray::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<ByteArray>>()
                assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
                assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PDF)
                assertThat(response.body).isNotEmpty()
            }
        }

        @Test
        fun `POST version preview with invalid version returns error`() = fixture {
            lateinit var testTenant: Tenant
            lateinit var templateId: String
            lateinit var variantKey: String

            given {
                testTenant = tenant("Test Tenant")
                val template = template(testTenant, "Invoice Template")
                templateId = template.id.value
                val variant = variant(testTenant, template)
                variantKey = variant.id.value
            }

            whenever {
                val headers = HttpHeaders()
                headers.contentType = MediaType.APPLICATION_JSON
                val request = HttpEntity("{}", headers)
                restTemplate.postForEntity(
                    "/tenants/${testTenant.id}/catalogs/default/templates/$templateId/variants/$variantKey/versions/999/preview",
                    request,
                    String::class.java,
                )
            }

            then {
                val response = result<org.springframework.http.ResponseEntity<String>>()
                assertThat(response.statusCode.value()).isGreaterThanOrEqualTo(400)
            }
        }
    }
}

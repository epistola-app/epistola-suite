package app.epistola.suite

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
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
class DuplicateIdHandlingTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `POST tenant with duplicate slug returns inline error`() = fixture {
        given {
            tenant("Existing Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "test-tenant-1")
            formData.add("name", "Duplicate Tenant")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity("/tenants", request, String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("A tenant with this ID already exists")
        }
    }

    @Test
    fun `POST environment with duplicate slug returns inline error`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            CreateEnvironment(
                id = EnvironmentId.of("production"),
                tenantId = tenant.id,
                name = "Production",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "production")
            formData.add("name", "Production Again")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/environments",
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("An environment with this ID already exists")
        }
    }

    @Test
    fun `POST theme with duplicate slug returns inline error`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            CreateTheme(
                id = ThemeId.of("my-theme"),
                tenantId = tenant.id,
                name = "My Theme",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "my-theme")
            formData.add("name", "My Theme Again")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/themes",
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("A theme with this ID already exists")
        }
    }

    @Test
    fun `POST template with duplicate slug returns inline error`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            CreateDocumentTemplate(
                id = TemplateId.of("my-template"),
                tenantId = tenant.id,
                name = "My Template",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "my-template")
            formData.add("name", "My Template Again")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/templates",
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("A template with this ID already exists")
        }
    }

    @Test
    fun `POST attribute with duplicate slug returns inline error`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "language")
            formData.add("displayName", "Language Again")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/attributes",
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("An attribute with this ID already exists")
        }
    }
}

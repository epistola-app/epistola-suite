package app.epistola.suite

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

class VariantTitleRequiredHandlerTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun postVariant(tenant: Tenant, templateKey: String, form: LinkedMultiValueMap<String, String>) = restTemplate.postForEntity(
        "/tenants/${tenant.id.value}/templates/default/$templateKey/variants",
        HttpEntity(
            form,
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                set("HX-Request", "true")
            },
        ),
        String::class.java,
    )

    private fun patchVariant(tenant: Tenant, templateKey: String, variantKey: String, form: LinkedMultiValueMap<String, String>) = restTemplate.exchange(
        "/tenants/${tenant.id.value}/templates/default/$templateKey/variants/$variantKey",
        HttpMethod.PATCH,
        HttpEntity(
            form,
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_FORM_URLENCODED
                set("HX-Request", "true")
            },
        ),
        String::class.java,
    )

    @Test
    fun `POST variant with a whitespace-only title is rejected and keeps the dialog open`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            CreateDocumentTemplate(
                id = TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant.id))),
                name = "Invoice",
            ).execute()
        }

        whenever {
            val form = LinkedMultiValueMap<String, String>()
            form.add("slug", "english-newsletter")
            form.add("title", "   ") // passes HTML5 `required`, trims to blank server-side
            postVariant(tenant, "invoice", form)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.body).contains("Title is required")
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
        }
    }

    @Test
    fun `POST variant with a duplicate id is rejected and keeps the dialog open`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            CreateVariant(
                id = VariantId(VariantKey.of("english"), templateId),
                title = "English",
                description = null,
            ).execute()
        }

        whenever {
            val form = LinkedMultiValueMap<String, String>()
            form.add("slug", "english")
            form.add("title", "English Again")
            postVariant(tenant, "invoice", form)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.body).contains("A variant with this ID already exists")
        }
    }

    @Test
    fun `PATCH variant with a whitespace-only title is rejected and keeps the edit dialog open`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            val templateId = TemplateId(TemplateKey.of("invoice"), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            CreateVariant(
                id = VariantId(VariantKey.of("english"), templateId),
                title = "English",
                description = null,
            ).execute()
        }

        whenever {
            val form = LinkedMultiValueMap<String, String>()
            form.add("title", "   ") // passes HTML5 `required`, trims to blank server-side
            patchVariant(tenant, "invoice", "english", form)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode.value()).isEqualTo(422)
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            assertThat(response.body).contains("Title is required")
            assertThat(response.body).contains("edit-variant-error")
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
        }
    }
}

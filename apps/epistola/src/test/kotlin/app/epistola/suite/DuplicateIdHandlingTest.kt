package app.epistola.suite

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

class DuplicateIdHandlingTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `POST tenant with duplicate slug returns inline error`() = fixture {
        lateinit var existingTenant: Tenant

        given {
            existingTenant = tenant("Existing Tenant")
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", existingTenant.id.value)
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
            val tenantId = TenantId(tenant.id)
            CreateEnvironment(
                id = EnvironmentId(EnvironmentKey.of("production"), tenantId),
                name = "Production",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
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
            val tenantId = TenantId(tenant.id)
            CreateTheme(
                id = ThemeId(ThemeKey.of("my-theme"), CatalogId.default(tenantId)),
                name = "My Theme",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "my-theme")
            formData.add("name", "My Theme Again")
            formData.add("catalog", "default")
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
            val tenantId = TenantId(tenant.id)
            CreateDocumentTemplate(
                id = TemplateId(TemplateKey.of("my-template"), CatalogId.default(tenantId)),
                name = "My Template",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "my-template")
            formData.add("name", "My Template Again")
            formData.add("catalog", "default")
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
            val tenantId = TenantId(tenant.id)
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "language")
            formData.add("displayName", "Language Again")
            formData.add("catalog", "default")
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

    @Test
    fun `POST code list with duplicate slug returns inline error`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            val tenantId = TenantId(tenant.id)
            CreateCodeList(
                id = CodeListId(CodeListKey.of("locales"), CatalogId.default(tenantId)),
                displayName = "Locales",
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("en", "English")),
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("catalog", "default")
            formData.add("slug", "locales")
            formData.add("displayName", "Locales Again")
            formData.add("sourceType", "INLINE")
            formData.add("entriesJson", """[{"code":"nl","label":"Dutch","sortOrder":0}]""")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/code-lists",
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("A code-list with this ID already exists")
        }
    }

    @Test
    fun `POST stencil with duplicate slug returns inline error on the slug field`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Test Tenant")
            val tenantId = TenantId(tenant.id)
            CreateStencil(
                id = StencilId(StencilKey.of("my-stencil"), CatalogId.default(tenantId)),
                name = "My Stencil",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            // Mirror the browser form: every input is submitted (incl. the empty
            // description/tags), so the re-rendered fragment's formData has those keys.
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("catalog", "default")
            formData.add("slug", "my-stencil")
            formData.add("name", "My Stencil Again")
            formData.add("description", "")
            formData.add("tags", "")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/stencils",
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("A stencil with this ID already exists")
            // Regression: the message must land on the slug field's span (it was
            // keyed under "id" — a field no form renders — and silently vanished).
            assertThat(response.body).contains("id=\"stencil-error-slug\"")
            assertThat(response.body).contains("data-error=\"true\"")
        }
    }

    @Test
    fun `POST catalog with duplicate slug returns inline error on the slug field`() = fixture {
        lateinit var tenant: Tenant

        given {
            tenant = tenant("Catalog Dup Tenant")
            CreateCatalog(
                tenantKey = tenant.id,
                id = CatalogKey.of("my-catalog"),
                name = "My Catalog",
            ).execute()
        }

        whenever {
            val headers = HttpHeaders()
            headers.contentType = MediaType.APPLICATION_FORM_URLENCODED
            headers.set("HX-Request", "true")
            val formData = LinkedMultiValueMap<String, String>()
            formData.add("slug", "my-catalog")
            formData.add("name", "My Catalog Again")
            val request = HttpEntity(formData, headers)
            restTemplate.postForEntity(
                "/tenants/${tenant.id}/catalogs/create",
                request,
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The handler now uses executeOrFormError: a duplicate maps to the slug field
            // (not a misleading catch-all), and a non-duplicate failure would propagate.
            assertThat(response.body).contains("A catalog with this ID already exists")
            assertThat(response.body).contains("id=\"catalog-error-slug\"")
            assertThat(response.body).contains("data-error=\"true\"")
        }
    }
}

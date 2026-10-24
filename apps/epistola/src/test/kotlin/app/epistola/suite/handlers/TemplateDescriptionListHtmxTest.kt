// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.default
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/** A template's description is taken by the create dialog and shown in the template list. */
class TemplateDescriptionListHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun htmxHeaders(form: Boolean = false) = HttpHeaders().apply {
        set("HX-Request", "true")
        if (form) contentType = MediaType.APPLICATION_FORM_URLENCODED
    }

    private fun create(tenant: Tenant, vararg params: Pair<String, String>): ResponseEntity<String> {
        val form = LinkedMultiValueMap<String, String>()
        params.forEach { (k, v) -> form.add(k, v) }
        return restTemplate.exchange("/tenants/${tenant.id}/catalogs/default/templates", HttpMethod.POST, HttpEntity(form, htmxHeaders(form = true)), String::class.java)
    }

    private fun get(url: String): ResponseEntity<String> = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(htmxHeaders()), String::class.java)

    @Test
    fun `the create dialog offers a description field bounded by the domain limit`() = fixture {
        lateinit var tenant: Tenant
        given { tenant = tenant("Template Description Dialog") }
        whenever { get("/tenants/${tenant.id}/catalogs/default/templates/new") }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val textarea = Regex("<textarea[^>]*name=\"description\"[^>]*>").find(response.body!!)?.value
            assertThat(textarea).isNotNull().contains("maxlength=\"2000\"").doesNotContain("required")
        }
    }

    @Test
    fun `creating a template with a description stores it`() = fixture {
        lateinit var tenant: Tenant
        given { tenant = tenant("Template Description Create") }
        whenever { create(tenant, "slug" to "described", "name" to "Described", "description" to "For letters to residents") }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode.is2xxSuccessful).isTrue()
            val stored = withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("described"), CatalogId.default(TenantId(tenant.id)))).query() }
            assertThat(stored!!.description).isEqualTo("For letters to residents")
        }
    }

    @Test
    fun `creating a template with an empty description stores none`() = fixture {
        lateinit var tenant: Tenant
        given { tenant = tenant("Template Description Empty") }
        whenever { create(tenant, "slug" to "plain", "name" to "Plain", "description" to "") }
        then {
            assertThat(result<ResponseEntity<String>>().statusCode.is2xxSuccessful).isTrue()
            val stored = withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("plain"), CatalogId.default(TenantId(tenant.id)))).query() }
            assertThat(stored!!.description).isNull()
        }
    }

    @Test
    fun `a description over the limit re-renders the form with its error and the value kept`() = fixture {
        lateinit var tenant: Tenant
        given { tenant = tenant("Template Description Too Long") }
        whenever { create(tenant, "slug" to "too-long", "name" to "Too long", "description" to "x".repeat(2001)) }
        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.body).contains("Description must be 2000 characters or less").contains("x".repeat(2001))
            assertThat(withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("too-long"), CatalogId.default(TenantId(tenant.id)))).query() }).isNull()
        }
    }

    @Test
    fun `the template list shows a template's description under its name`() = fixture {
        lateinit var tenant: Tenant
        given {
            tenant = tenant("Template Description List")
            val catalog = CatalogId.default(TenantId(tenant.id))
            CreateDocumentTemplate(TemplateId(TemplateKey.of("described"), catalog), "Described", description = "For letters to residents & companies").execute()
            CreateDocumentTemplate(TemplateId(TemplateKey.of("plain"), catalog), "Plain").execute()
        }
        whenever { get("/tenants/${tenant.id}/catalogs/default/templates") }
        then {
            val body = result<ResponseEntity<String>>().body!!
            val rows = Regex("<tr[^>]*data-testid=\"template-row\"[^>]*data-template-slug=\"([^\"]+)\"[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
                .findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            assertThat(rows.getValue("described")).contains("data-testid=\"template-description\"").contains("For letters to residents &amp; companies")
            assertThat(rows.getValue("plain")).doesNotContain("template-description")
        }
    }
}

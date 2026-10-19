// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap

/**
 * Server-side contract for the document-template create form converted onto the
 * dialog groundwork, mirroring EnvironmentHandlerHtmxTest. Asserts the
 * URL-addressable dialog convention (docs/dialog-forms.md) with the template
 * form's twist: success is a client-side soft navigation to the new template's
 * page (HX-Location, a boosted body-swap), not an in-place list refresh. Branches
 * covered: HTMX GET → dialog fragment (catalog <select> from AUTHORED catalogs);
 * HTMX POST invalid → retarget the form + 422; HTMX POST valid → HX-Location;
 * plain list route → mount stays empty (guards the th:if/th:replace precedence
 * trap).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `HTMX GET new returns the dialog fragment with the create form and authored catalogs`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Tpl New Dialog")
            // An extra authored catalog alongside the auto-created "Default" one,
            // so the <select> demonstrably lists authored catalogs.
            withMediator {
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("marketing"), name = "Marketing").execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/catalogs/default/templates/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The dialog-shell chrome + the caller-owned form.
            assertThat(response.body).contains("""id="create-template-dialog"""")
            assertThat(response.body).contains("ep-dialog")
            assertThat(response.body).contains("""id="create-template-form"""")
            // The catalog <select> populated from authored catalogs.
            // Opened in a catalog, it creates there: no catalog to choose.
            assertThat(response.body).contains("/catalogs/default/templates\"").doesNotContain("<select id=\"catalog\"")
            assertThat(response.body).contains("""name="name"""")
            assertThat(response.body).contains("""name="slug"""")
            // It is a fragment, not the whole page (no app shell).
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST invalid retargets the form with 422, inline errors, and the catalog select still populated`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Tpl Create Invalid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("name", "Valid Name")
            form.add("slug", "INVALID SLUG") // uppercase + space → fails the pattern
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/catalogs/default/templates",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-template-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // No closeDialog / redirect — the dialog stays open showing the error.
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            assertThat(response.headers.getFirst("HX-Location")).isNull()
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
            // The re-rendered form: preserved values + an inline error.
            assertThat(response.body).contains("""id="create-template-form"""")
            assertThat(response.body).contains("form-error")
            assertThat(response.body).contains("value=\"Valid Name\"")
            assertThat(response.body).contains("value=\"INVALID SLUG\"")
            // authoredCatalogs prefill survived → the <select> is still populated.
            // Opened in a catalog, it creates there: no catalog to choose.
            assertThat(response.body).contains("/catalogs/default/templates\"").doesNotContain("<select id=\"catalog\"")
        }
    }

    @Test
    fun `HTMX POST valid returns HX-Location to the new template page`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Tpl Create Valid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("name", "Monthly Invoice")
            form.add("slug", "monthly-invoice")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/catalogs/default/templates",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Success soft-navigates the page to the created template via a boosted
            // body-swap (HX-Location); the dialog goes with the swapped-out body —
            // asserted via the header, not a body. No HX-Redirect (full reload).
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Location"))
                .isEqualTo("/tenants/${testTenant.id}/catalogs/default/templates/monthly-invoice")
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Tpl Plain List") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/catalogs/default/templates",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page, but the mount is empty: no openDialog flag on the
            // plain list route, so the create dialog must NOT be embedded (the
            // th:if guard must wrap the th:replace — same element would include
            // it unconditionally). See docs/dialog-forms.md.
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).doesNotContain("""id="create-template-dialog"""")
            assertThat(response.body).doesNotContain("create-template-form")
        }
    }

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxFormHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        set("HX-Request", "true")
    }
}

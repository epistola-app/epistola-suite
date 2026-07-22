package app.epistola.suite.themes

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
 * Server-side contract for the theme create form converted onto the dialog
 * groundwork, mirroring TemplateHandlerHtmxTest. Asserts the URL-addressable
 * dialog convention (docs/dialog-forms.md) with the theme form's twist: success
 * is a client-side soft navigation to the new theme's page (HX-Location, a
 * boosted body-swap), not an in-place list refresh. Branches covered: HTMX GET →
 * dialog fragment (catalog <select> from AUTHORED catalogs); HTMX POST invalid →
 * retarget the form + 422; HTMX POST valid → HX-Location; plain list route →
 * mount stays empty (guards the th:if/th:replace precedence trap).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ThemeHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `HTMX GET new returns the dialog fragment with the create form and authored catalogs`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Theme New Dialog")
            // An extra authored catalog alongside the auto-created "Default" one,
            // so the <select> demonstrably lists authored catalogs.
            withMediator {
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("marketing"), name = "Marketing").execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/themes/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The dialog-shell chrome + the caller-owned form.
            assertThat(response.body).contains("""id="create-theme-dialog"""")
            assertThat(response.body).contains("ep-dialog")
            assertThat(response.body).contains("""id="create-theme-form"""")
            // The catalog <select> populated from authored catalogs.
            assertThat(response.body).contains("""name="catalog"""")
            assertThat(response.body).contains("Marketing")
            assertThat(response.body).contains("Default")
            assertThat(response.body).contains("""name="name"""")
            assertThat(response.body).contains("""name="slug"""")
            assertThat(response.body).contains("""name="description"""")
            // It is a fragment, not the whole page (no app shell).
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST invalid retargets the form with 422, inline errors, and the catalog select still populated`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Theme Create Invalid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("name", "Valid Name")
            form.add("slug", "INVALID SLUG") // uppercase + space → fails the pattern
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/themes",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-theme-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // No closeDialog / redirect — the dialog stays open showing the error.
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
            // The re-rendered form: preserved values + an inline error.
            assertThat(response.body).contains("""id="create-theme-form"""")
            assertThat(response.body).contains("form-error")
            assertThat(response.body).contains("value=\"Valid Name\"")
            assertThat(response.body).contains("value=\"INVALID SLUG\"")
            // authoredCatalogs prefill survived → the <select> is still populated.
            assertThat(response.body).contains("""name="catalog"""")
            assertThat(response.body).contains("Default")
        }
    }

    @Test
    fun `HTMX POST valid returns HX-Location to the new theme page`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Theme Create Valid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("name", "Corporate Theme")
            form.add("slug", "corporate")
            form.add("description", "Our house style")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/themes",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            // Success soft-navigates the page to the created theme via a boosted
            // body-swap (HX-Location), so the dialog goes with the swapped-out body
            // — asserted via the header, not a body. No HX-Redirect (full reload).
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Location"))
                .isEqualTo("/tenants/${testTenant.id}/themes/default/corporate")
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Theme Plain List") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/themes",
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
            assertThat(response.body).doesNotContain("""id="create-theme-dialog"""")
            assertThat(response.body).doesNotContain("create-theme-form")
        }
    }

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxFormHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        set("HX-Request", "true")
    }
}

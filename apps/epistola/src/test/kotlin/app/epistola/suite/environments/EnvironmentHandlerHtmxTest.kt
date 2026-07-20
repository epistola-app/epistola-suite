package app.epistola.suite.environments

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
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
 * Exemplar server-side contract for the environment create form converted onto
 * the dialog groundwork (Phase 2 Chunk A). Every other form's conversion copies
 * this shape. Asserts the four branches of the URL-addressable dialog convention
 * (docs/dialog-forms.md): HTMX GET → dialog fragment; HTMX POST invalid →
 * retarget the form + 422; HTMX POST valid → closeDialog + OOB list refresh;
 * non-HTMX GET → host list page with the dialog embedded.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnvironmentHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `HTMX GET new returns the dialog fragment with the create form`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Env New Dialog") }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/environments/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The dialog-shell chrome + the caller-owned form.
            assertThat(response.body).contains("""id="create-environment-dialog"""")
            assertThat(response.body).contains("ep-dialog")
            assertThat(response.body).contains("""id="create-environment-form"""")
            assertThat(response.body).contains("""name="name"""")
            assertThat(response.body).contains("""name="slug"""")
            // It is a fragment, not the whole page (no app shell nav).
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `history-restore GET new renders the full host page, not a bare dialog fragment`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Env New HistoryRestore") }

        whenever {
            // A cold-cache history restore: HTMX re-requests the URL with
            // HX-Request + HX-History-Restore-Request and swaps the response in as
            // the WHOLE page body. Returning only the dialog fragment would blank
            // the page (no shell/nav/list).
            val headers = HttpHeaders().apply {
                set("HX-Request", "true")
                set("HX-History-Restore-Request", "true")
            }
            restTemplate.exchange(
                "/tenants/${testTenant.id}/environments/new",
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page (app shell), NOT a lone fragment — the whole-page swap
            // lands the list + embedded dialog, not a stray <dialog>.
            assertThat(response.body).contains("<html")
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).contains("""id="create-environment-dialog"""")
        }
    }

    @Test
    fun `HTMX POST invalid retargets the form with 422 and inline errors`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Env Create Invalid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("name", "Production")
            form.add("slug", "INVALID SLUG") // uppercase + space → fails asEnvironmentId
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/environments",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-environment-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // No closeDialog — the dialog stays open showing the error.
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            // The re-rendered form: preserved value + an inline error.
            assertThat(response.body).contains("""id="create-environment-form"""")
            assertThat(response.body).contains("form-error")
            // tenantId prefill made it into the model → the action URL is intact.
            assertThat(response.body).contains("/tenants/${testTenant.id}/environments")
        }
    }

    @Test
    fun `HTMX POST duplicate slug goes through the same field-error path`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Env Create Duplicate")
            withMediator {
                CreateEnvironment(
                    id = EnvironmentId(EnvironmentKey.of("staging"), TenantId(testTenant.id)),
                    name = "Staging",
                ).execute()
            }
        }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("name", "Staging Again")
            form.add("slug", "staging") // already exists
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/environments",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-environment-form")
            assertThat(response.body).contains("already exists")
        }
    }

    @Test
    fun `HTMX POST valid closes the dialog and refreshes the list OOB with the new row`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Env Create Valid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("name", "Production US")
            form.add("slug", "prod-us")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/environments",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Server-driven close, plus the distinct success event the search-box
            // reset listens on (never emitted by non-success closeDialog closes).
            assertThat(response.headers.getFirst("HX-Trigger"))
                .isEqualTo("""{"closeDialog": null, "dialogSuccess": null}""")
            // No primary swap: the list refreshes out-of-band.
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("none")
            // The OOB list fragment carries its id + hx-swap-oob and the new row.
            assertThat(response.body).contains("""id="environment-list"""")
            assertThat(response.body).contains("hx-swap-oob=\"outerHTML\"")
            assertThat(response.body).contains("Production US")
            assertThat(response.body).contains("prod-us")
            // The auth-gated delete button rendered → `auth` reached the OOB model.
            assertThat(response.body).contains("Delete environment")
        }
    }

    @Test
    fun `HTMX delete of the only environment flips the list to the empty state`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Env Delete Only")
            withMediator {
                CreateEnvironment(
                    id = EnvironmentId(EnvironmentKey.of("staging"), TenantId(testTenant.id)),
                    name = "Staging",
                ).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/environments/staging/delete",
                HttpMethod.POST,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The whole list region came back so the empty-state (outside the rows
            // tbody) can replace the table.
            assertThat(response.body).contains("""id="environment-list"""")
            assertThat(response.body).contains("No environments yet")
            // CR5: even when empty, the #environment-rows tbody (the header search
            // box's hx-target) stays in the DOM — the table is hidden, not removed —
            // so typing in search doesn't fire htmx:targetError.
            assertThat(response.body).contains("""id="environment-rows"""")
            // No data rows and no full page — just the refreshed fragment. (The
            // thead is th:if-gated when empty, so no header <tr> either.)
            assertThat(response.body).doesNotContain("<tr")
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX delete of one of several environments keeps the survivors`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Env Delete Survivors")
            withMediator {
                CreateEnvironment(
                    id = EnvironmentId(EnvironmentKey.of("staging"), TenantId(testTenant.id)),
                    name = "Staging",
                ).execute()
                CreateEnvironment(
                    id = EnvironmentId(EnvironmentKey.of("prod"), TenantId(testTenant.id)),
                    name = "Production",
                ).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/environments/staging/delete",
                HttpMethod.POST,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("""id="environment-list"""")
            // Survivor stays; the deleted one is gone.
            assertThat(response.body).contains("Production")
            assertThat(response.body).doesNotContain("Staging")
            // The auth-gated delete button rendered → `auth` reached the
            // single-fragment render through the interceptor.
            assertThat(response.body).contains("Delete environment")
        }
    }

    @Test
    fun `non-HTMX GET new renders the list page with the dialog embedded and open`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Env New DirectNav") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/environments/new",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page (app shell) …
            assertThat(response.body).contains("<html")
            assertThat(response.body).contains("Environments")
            // … with the dialog embedded in the mount (plain <dialog>; the client
            // opens it on load).
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).contains("""id="create-environment-dialog"""")
            assertThat(response.body).contains("""id="create-environment-form"""")
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Env Plain List") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/environments",
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
            assertThat(response.body).doesNotContain("""id="create-environment-dialog"""")
            assertThat(response.body).doesNotContain("create-environment-form")
        }
    }

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxFormHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        set("HX-Request", "true")
    }
}

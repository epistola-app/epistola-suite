// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
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
import org.springframework.util.MultiValueMap

/**
 * Deterministic regression cover for the code-list delete HTMX flow.
 *
 * Replaces the browser test `CodeListUiTest > delete code list from detail
 * page swaps back to list without htmx target error`, which was `@Disabled`
 * as #418 Instance A: it timed out racing the confirm-dialog's inline-script
 * binding before its actual regression assertion was ever reached.
 *
 * The original regression (commit 1c33bcb9): `CodeListHandler.delete`
 * previously returned `303 See Other` on HTMX requests; HTMX followed the
 * redirect and swapped into the now-vanished `hx-target` → `htmx:targetError`
 * in the console. The fix makes the HTMX path return `200` + an `HX-Push-Url`
 * header + the list fragment. Asserting that contract directly (no Playwright)
 * covers the bug class better than observing the `htmx:targetError` symptom
 * through a browser, and is immune to dialog-binding timing.
 *
 * Also covers the code-list create form converted onto the dialog groundwork
 * (docs/dialog-forms.md): the dialog is list-launched with the authored-catalog
 * <select> prefill and a sourceType radio-pane cascade; on SUCCESS it soft-
 * navigates to the new code list's own detail page (HX-Location, a boosted
 * body-swap), mirroring the template conversion. HTMX GET → dialog fragment; HTMX
 * POST invalid → retarget the form + 422; HTMX POST valid → HX-Location to the
 * detail page; non-HTMX GET → host list page with the dialog embedded; plain list
 * route omits the dialog.
 */
class CodeListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `HTMX delete returns 200 with list fragment and HX-Push-Url, never a 3xx`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedDeletableCodeList("Htmx Delete OK")
        }

        whenever {
            postHtmx("/tenants/${seeded.tenantKey}/code-lists/${seeded.catalogKey}/${seeded.codeListKey}/delete")
        }

        then {
            val response = result<ResponseEntity<String>>()
            // The crux of #418-A: an HTMX delete must be 200, NOT a 3xx. A 3xx
            // here is exactly what produced htmx:targetError in the browser.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Code lists")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${seeded.tenantKey}/code-lists")
        }
    }

    @Test
    fun `non-HTMX delete redirects the browser to the code-lists list`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedDeletableCodeList("Plain Delete Redirect")
        }

        whenever {
            // TestRestTemplate follows redirects exactly as a browser would,
            // so the progressive-enhancement path (handler returns 303 →
            // GET the list) is observed end-to-end as the final 200 list page.
            postPlain("/tenants/${seeded.tenantKey}/code-lists/${seeded.catalogKey}/${seeded.codeListKey}/delete")
        }

        then {
            val response = result<ResponseEntity<String>>()
            // A normal form POST lands back on the code-lists list — NOT a JSON
            // blob and NOT an error page. (Deterministic regardless of client
            // redirect config: a non-following client would 303 here, but the
            // autoconfigured one follows, matching real browser behavior.)
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("Code lists")
            assertThat(response.body).doesNotContain("\"error\"")
            assertThat(response.body).doesNotContain("still referenced")
        }
    }

    @Test
    fun `HTMX delete of an in-use code list is rejected with 400 JSON`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedInUseCodeList("Htmx Delete In Use")
        }

        whenever {
            postHtmx("/tenants/${seeded.tenantKey}/code-lists/${seeded.catalogKey}/${seeded.codeListKey}/delete")
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
            assertThat(response.body).contains("\"error\"")
        }
    }

    @Test
    fun `non-HTMX delete of an in-use code list re-renders the detail page with the error`() = fixture {
        lateinit var seeded: Seeded

        given {
            seeded = seedInUseCodeList("Plain Delete In Use")
        }

        whenever {
            postPlain("/tenants/${seeded.tenantKey}/code-lists/${seeded.catalogKey}/${seeded.codeListKey}/delete")
        }

        then {
            val response = result<ResponseEntity<String>>()
            // No HTMX → the handler re-renders the detail page (200) with the
            // in-use message inline rather than a JSON 400.
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("still referenced")
        }
    }

    @Test
    fun `HTMX GET new returns the dialog fragment with the create form and authored catalogs`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Code List New Dialog")
            withMediator {
                // An extra authored catalog alongside the auto-created "Default"
                // one, so the catalog <select> demonstrably lists authored catalogs.
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("marketing"), name = "Marketing").execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/code-lists/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The dialog-shell chrome + the caller-owned form.
            assertThat(response.body).contains("""id="create-code-list-dialog"""")
            assertThat(response.body).contains("""id="create-code-list-form"""")
            // The radio-pane cascade form + its fields.
            assertThat(response.body).contains("""data-radio-panes="sourceType"""")
            assertThat(response.body).contains("""name="displayName"""")
            assertThat(response.body).contains("""name="slug"""")
            // The catalog <select> populated from authored catalogs.
            assertThat(response.body).contains("""name="catalog"""")
            assertThat(response.body).contains("Marketing")
            // It is a fragment, not the whole page (no app shell).
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST invalid retargets the form with 422 and inline errors`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Code List Create Invalid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("displayName", "Valid Name")
            form.add("slug", "BAD SLUG") // uppercase + space → fails asCodeListId
            form.add("sourceType", "INLINE")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/code-lists",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-code-list-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // No closeDialog — the dialog stays open showing the error.
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            // The re-rendered form: preserved values + an inline error.
            assertThat(response.body).contains("""id="create-code-list-form"""")
            assertThat(response.body).contains("form-error")
            assertThat(response.body).contains("value=\"Valid Name\"")
        }
    }

    @Test
    fun `HTMX POST valid soft-navigates to the code-list detail page`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Code List Create Valid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("displayName", "Languages")
            form.add("slug", "languages")
            form.add("sourceType", "INLINE")
            form.add("entriesJson", """[{"code":"en","label":"English"}]""")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/code-lists",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Soft boosted navigation to the new code list's own detail page —
            // HX-Location, no OOB refresh, no closeDialog (the page navigates away).
            assertThat(response.headers.getFirst("HX-Location"))
                .isEqualTo("/tenants/${testTenant.id}/code-lists/default/languages")
            assertThat(response.headers.getFirst("HX-Redirect")).isNull()
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            // Persistence verified through the mediator, not the (absent) body.
            val persisted = withMediator {
                GetCodeList(
                    CodeListId(CodeListKey.of("languages"), CatalogId.default(TenantId(testTenant.id))),
                ).query()
            }
            assertThat(persisted).isNotNull
            assertThat(persisted!!.displayName).isEqualTo("Languages")
        }
    }

    @Test
    fun `non-HTMX GET new renders the list page with the dialog embedded and open`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Code List New DirectNav") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/code-lists/new",
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page (app shell) …
            assertThat(response.body).contains("<html")
            // … with the dialog embedded in the mount (plain <dialog>; the client
            // opens it on load).
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).contains("""id="create-code-list-dialog"""")
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Code List Plain List") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/code-lists",
                String::class.java,
            )
        }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page, but the mount is empty: no openDialog flag on the
            // plain list route, so the create dialog must NOT be embedded (the
            // th:if guard must wrap the th:replace). See docs/dialog-forms.md.
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).doesNotContain("""id="create-code-list-dialog"""")
        }
    }

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxFormHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        set("HX-Request", "true")
    }

    private data class Seeded(
        val tenantKey: String,
        val catalogKey: String,
        val codeListKey: String,
    )

    private fun seedDeletableCodeList(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("deletable"), catalogId)
        CreateCodeList(
            id = codeListId,
            displayName = "Deletable",
            sourceType = CodeListSource.INLINE,
            entries = listOf(CodeListEntry("test", "Test")),
        ).execute()
        Seeded(tenantId.key.value, codeListId.catalogKey.value, codeListId.key.value)
    }

    private fun seedInUseCodeList(name: String): Seeded = withMediator {
        val tenant: Tenant = createTenant(name)
        val tenantId = TenantId(tenant.id)
        val catalogId = CatalogId.default(tenantId)
        val codeListId = CodeListId(CodeListKey.of("in-use"), catalogId)
        CreateCodeList(
            id = codeListId,
            displayName = "In Use",
            sourceType = CodeListSource.INLINE,
            entries = listOf(CodeListEntry("a", "A")),
        ).execute()
        // An attribute binding keeps the code list in use → DeleteCodeList
        // throws CodeListInUseException.
        CreateAttributeDefinition(
            id = AttributeId(AttributeKey.of("bound"), catalogId),
            displayName = "Bound",
            codeListId = codeListId,
        ).execute()
        Seeded(tenantId.key.value, codeListId.catalogKey.value, codeListId.key.value)
    }

    private fun postHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.postForEntity(url, HttpEntity<Void>(headers), String::class.java)
    }

    /** A plain form POST (no HX-Request) — the progressive-enhancement path. */
    private fun postPlain(url: String): ResponseEntity<String> = restTemplate.postForEntity(url, HttpEntity<Void>(HttpHeaders()), String::class.java)
}

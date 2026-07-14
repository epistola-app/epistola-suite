package app.epistola.suite.attributes

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
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
 * Server-side contract for the attribute create form converted onto the dialog
 * groundwork. The dialog is list-launched with the TEMPLATE prefill
 * (authored-catalog <select>) and a radio-pane cascade (constraintKind → inline /
 * code-list panes); on success it REDIRECTS to the list (the list carries a
 * catalog filter, so a full navigation keeps the visible rows in step with the
 * active filter/URL) while delete still refreshes the whole region. Asserts the
 * URL-addressable dialog convention (docs/dialog-forms.md): HTMX GET → dialog
 * fragment; HTMX POST invalid → retarget the form + 422; HTMX POST valid →
 * HX-Redirect to the attribute list; non-HTMX GET → host list page with the
 * dialog embedded; delete → region refresh.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AttributeHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `HTMX GET new returns the dialog fragment with the create form, catalogs and code lists`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Attr New Dialog")
            withMediator {
                // An extra authored catalog alongside the auto-created "Default" one,
                // so the catalog <select> demonstrably lists authored catalogs.
                CreateCatalog(tenantKey = testTenant.id, id = CatalogKey.of("marketing"), name = "Marketing").execute()
                // A code list so the code-list <select> is populated too.
                CreateCodeList(
                    id = CodeListId(CodeListKey.of("locales"), CatalogId.default(TenantId(testTenant.id))),
                    displayName = "Locales",
                    sourceType = CodeListSource.INLINE,
                    entries = listOf(CodeListEntry("en", "English")),
                ).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/attributes/new",
                HttpMethod.GET,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The dialog-shell chrome + the caller-owned form.
            assertThat(response.body).contains("""id="create-attribute-dialog"""")
            assertThat(response.body).contains("ep-dialog")
            assertThat(response.body).contains("""id="create-attribute-form"""")
            // The radio-pane cascade form + its fields.
            assertThat(response.body).contains("""data-radio-panes="constraintKind"""")
            assertThat(response.body).contains("""name="displayName"""")
            assertThat(response.body).contains("""name="slug"""")
            // The catalog <select> populated from authored catalogs.
            assertThat(response.body).contains("""name="catalog"""")
            assertThat(response.body).contains("Marketing")
            assertThat(response.body).contains("Default")
            // The code-list <select> populated from code lists.
            assertThat(response.body).contains("""name="codeList"""")
            assertThat(response.body).contains("Locales")
            // It is a fragment, not the whole page (no app shell).
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `HTMX POST invalid retargets the form with 422, inline errors, and prefills survive`() = fixture {
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Attr Create Invalid")
            withMediator {
                CreateCodeList(
                    id = CodeListId(CodeListKey.of("locales"), CatalogId.default(TenantId(testTenant.id))),
                    displayName = "Locales",
                    sourceType = CodeListSource.INLINE,
                    entries = listOf(CodeListEntry("en", "English")),
                ).execute()
            }
        }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("displayName", "Valid Name")
            form.add("slug", "INVALID SLUG") // uppercase + space → fails asAttributeId
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/attributes",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT)
            // Retargets the FORM (not the dialog, not the list) so the open modal
            // dialog is never removed from the top layer.
            assertThat(response.headers.getFirst("HX-Retarget")).isEqualTo("#create-attribute-form")
            assertThat(response.headers.getFirst("HX-Reswap")).isEqualTo("outerHTML")
            // No closeDialog — the dialog stays open showing the error.
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            // The re-rendered form: preserved values + an inline error.
            assertThat(response.body).contains("""id="create-attribute-form"""")
            assertThat(response.body).contains("form-error")
            assertThat(response.body).contains("value=\"Valid Name\"")
            assertThat(response.body).contains("value=\"INVALID SLUG\"")
            // Prefills survived → both <select>s are still populated.
            assertThat(response.body).contains("""name="catalog"""")
            assertThat(response.body).contains("Default")
            assertThat(response.body).contains("""name="codeList"""")
            assertThat(response.body).contains("Locales")
        }
    }

    @Test
    fun `HTMX POST valid redirects to the attribute list`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Attr Create Valid") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("displayName", "Language")
            form.add("slug", "language")
            form.add("constraintKind", "free")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/attributes",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full navigation to the list replaces the whole page — HX-Redirect,
            // no OOB refresh, no closeDialog (the page navigates away entirely).
            assertThat(response.headers.getFirst("HX-Redirect")).isEqualTo("/tenants/${testTenant.id}/attributes")
            assertThat(response.headers.getFirst("HX-Trigger")).isNull()
            // Persistence verified through the mediator, not the (absent) list body.
            val persisted = withMediator {
                GetAttributeDefinition(
                    id = AttributeId(
                        AttributeKey.of("language"),
                        CatalogId.default(TenantId(testTenant.id)),
                    ),
                ).query()
            }
            assertThat(persisted).isNotNull
            assertThat(persisted!!.displayName).isEqualTo("Language")
        }
    }

    @Test
    fun `HTMX POST valid with inline constraint persists the allowed values`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Attr Create Inline") }

        whenever {
            val form: MultiValueMap<String, String> = LinkedMultiValueMap()
            form.add("catalog", "default")
            form.add("displayName", "Language")
            form.add("slug", "language")
            form.add("constraintKind", "inline")
            form.add("allowedValues", "dutch\nenglish\ngerman")
            restTemplate.postForEntity(
                "/tenants/${testTenant.id}/attributes",
                HttpEntity(form, htmxFormHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.headers.getFirst("HX-Redirect")).isEqualTo("/tenants/${testTenant.id}/attributes")
            // The inline constraint was parsed and persisted — verified through the
            // mediator (the redirect returns no list body to inspect).
            val persisted = withMediator {
                GetAttributeDefinition(
                    id = AttributeId(
                        AttributeKey.of("language"),
                        CatalogId.default(TenantId(testTenant.id)),
                    ),
                ).query()
            }
            assertThat(persisted).isNotNull
            assertThat(persisted!!.allowedValues).containsExactly("dutch", "english", "german")
        }
    }

    @Test
    fun `HTMX delete refreshes the whole list region and drops the deleted row`() = fixture {
        // NOTE: unlike environments, a tenant always carries SUBSCRIBED system-catalog
        // attributes (e.g. country/locale), so the attribute list is never truly empty
        // and the empty-state terminal cannot be reached by deleting authored rows. This
        // asserts the identical delete mechanism environments use — the whole
        // #attribute-list region is returned (outerHTML), the deleted row is gone, and a
        // survivor remains — which is what makes the last-delete empty-state flip work
        // when a list ever does empty out.
        lateinit var testTenant: Tenant

        given {
            testTenant = tenant("Attr Delete Region")
            withMediator {
                CreateAttributeDefinition(
                    id = AttributeId(AttributeKey.of("brand"), CatalogId.default(TenantId(testTenant.id))),
                    displayName = "Brand Channel",
                ).execute()
                CreateAttributeDefinition(
                    id = AttributeId(AttributeKey.of("audience"), CatalogId.default(TenantId(testTenant.id))),
                    displayName = "Audience Segment",
                ).execute()
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${testTenant.id}/attributes/default/brand/delete",
                HttpMethod.POST,
                HttpEntity<Void>(htmxHeaders()),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // The whole list region came back (outerHTML swap into #attribute-list),
            // not just the rows tbody.
            assertThat(response.body).contains("""id="attribute-list"""")
            // Survivor stays; the deleted authored attribute is gone.
            assertThat(response.body).contains("Audience Segment")
            assertThat(response.body).doesNotContain("Brand Channel")
            // The auth-gated delete control rendered → `auth` reached the fragment model.
            assertThat(response.body).contains("Delete attribute")
            // Just the refreshed fragment, no full page.
            assertThat(response.body).doesNotContain("<html")
        }
    }

    @Test
    fun `non-HTMX GET new renders the list page with the dialog embedded and open`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Attr New DirectNav") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/attributes/new",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page (app shell) …
            assertThat(response.body).contains("<html")
            assertThat(response.body).contains("Attributes")
            // … with the dialog embedded in the mount (plain <dialog>; the client
            // opens it on load).
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).contains("""id="create-attribute-dialog"""")
            assertThat(response.body).contains("""id="create-attribute-form"""")
        }
    }

    @Test
    fun `plain list route does not embed the create dialog`() = fixture {
        lateinit var testTenant: Tenant

        given { testTenant = tenant("Attr Plain List") }

        whenever {
            restTemplate.getForEntity(
                "/tenants/${testTenant.id}/attributes",
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            // Full host page, but the mount is empty: no openDialog flag on the
            // plain list route, so the create dialog must NOT be embedded (the
            // th:if guard must wrap the th:replace). See docs/dialog-forms.md.
            assertThat(response.body).contains("""id="dialog-mount"""")
            assertThat(response.body).doesNotContain("""id="create-attribute-dialog"""")
            assertThat(response.body).doesNotContain("create-attribute-form")
        }
    }

    private fun htmxHeaders() = HttpHeaders().apply { set("HX-Request", "true") }

    private fun htmxFormHeaders() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        set("HX-Request", "true")
    }
}

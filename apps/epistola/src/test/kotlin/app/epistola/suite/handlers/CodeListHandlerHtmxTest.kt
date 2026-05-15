package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.EpistolaSuiteApplication
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

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
 */
@SpringBootTest(classes = [EpistolaSuiteApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
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

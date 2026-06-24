package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * The catalog list mutates through a single always-present `#catalog-list`
 * swap region (regression for the broken table layout: an empty→first-catalog
 * create previously had no swap target and returned a bare `<tbody>` the
 * browser fragment parser relocated). Every list mutation must return that
 * wrapper containing *either* the table *or* the empty state.
 */
class CatalogListHandlerTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun htmxForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        add("HX-Request", "true")
    }

    private fun plainForm() = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
    }

    @Test
    fun `creating a catalog from the form page redirects to the list, which then shows it`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog List Create") }

        whenever {
            // The New Catalog form is a full page (not a dialog): a plain POST that
            // redirects back to the list on success.
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("slug", "list-region-cat")
            payload.add("name", "List Region Cat")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/create",
                HttpEntity(payload, plainForm()),
                String::class.java,
            )
        }

        then {
            // The list now renders the new catalog inside the stable #catalog-list region.
            val list = restTemplate.getForEntity("/tenants/${t.id}/catalogs", String::class.java)
            assertThat(list.statusCode).isEqualTo(HttpStatus.OK)
            val body = list.body!!
            assertThat(body).contains("id=\"catalog-list\"")
            assertThat(body).contains("<table")
            assertThat(body).contains("List Region Cat")
        }
    }

    @Test
    fun `the New Catalog form page renders`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog New Form") }

        whenever { restTemplate.getForEntity("/tenants/${t.id}/catalogs/new", String::class.java) }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!).contains("New Catalog").contains("name=\"slug\"")
        }
    }

    @Test
    fun `the Subscribe form page renders`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Subscribe Form") }

        whenever { restTemplate.getForEntity("/tenants/${t.id}/catalogs/subscribe", String::class.java) }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!).contains("Subscribe to Catalog").contains("name=\"sourceUrl\"")
        }
    }

    @Test
    fun `the Import ZIP form page renders`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Import Form") }

        whenever { restTemplate.getForEntity("/tenants/${t.id}/catalogs/import", String::class.java) }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!).contains("Import Catalog from ZIP").contains("multipart/form-data")
        }
    }

    @Test
    fun `creating a catalog with an invalid slug re-renders the form page with an error`() = fixture {
        lateinit var t: Tenant
        given { t = tenant("Catalog Create Error") }

        whenever {
            val payload = LinkedMultiValueMap<String, String>()
            payload.add("slug", "X") // too short + uppercase: fails the slug pattern
            payload.add("name", "Bad Slug Cat")
            restTemplate.postForEntity(
                "/tenants/${t.id}/catalogs/create",
                HttpEntity(payload, plainForm()),
                String::class.java,
            )
        }

        then {
            // Page flow: a validation failure re-renders the New Catalog form (200) with the
            // error, rather than redirecting.
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body!!).contains("New Catalog").contains("required")
        }
    }

    @Test
    fun `removing a catalog returns the whole #catalog-list region (parser-safe, not a bare tbody)`() = fixture {
        lateinit var t: Tenant
        given {
            t = tenant("Catalog List Remove")
            listOf("keep-cat" to "Keep Cat", "gone-cat" to "Gone Cat").forEach { (slug, name) ->
                val p = LinkedMultiValueMap<String, String>()
                p.add("slug", slug)
                p.add("name", name)
                restTemplate.postForEntity("/tenants/${t.id}/catalogs/create", HttpEntity(p, htmxForm()), String::class.java)
            }
        }

        whenever {
            restTemplate.exchange(
                "/tenants/${t.id}/catalogs/gone-cat/delete",
                HttpMethod.POST,
                HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
                String::class.java,
            )
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // The unregister response is the full stable region containing a
            // complete <table> (not a bare <tbody> the browser would relocate),
            // with the removed catalog gone and the survivor still listed.
            assertThat(body).contains("id=\"catalog-list\"")
            assertThat(body).contains("<table").contains("</table>")
            assertThat(body).contains("Keep Cat")
            assertThat(body).doesNotContain("Gone Cat")
        }
    }

    @Test
    fun `a drifted AUTHORED catalog shows 'pending changes' in the Version column - a clean one does not`() = fixture {
        lateinit var t: Tenant
        given {
            t = tenant("Catalog List Drift")
            withMediator {
                // Clean: released, untouched.
                val clean = CatalogKey.of("clean-cat")
                CreateCatalog(tenantKey = t.id, id = clean, name = "Clean Cat").execute()
                CreateTheme(id = ThemeId(ThemeKey.of("ct1"), CatalogId(clean, TenantId(t.id))), name = "C1").execute()
                ReleaseCatalogVersion(tenantKey = t.id, catalogKey = clean, version = "1.0.0").execute()

                // Drifted: released, then edited.
                val drift = CatalogKey.of("drift-cat")
                CreateCatalog(tenantKey = t.id, id = drift, name = "Drift Cat").execute()
                CreateTheme(id = ThemeId(ThemeKey.of("dt1"), CatalogId(drift, TenantId(t.id))), name = "D1").execute()
                ReleaseCatalogVersion(tenantKey = t.id, catalogKey = drift, version = "2.0.0").execute()
                CreateTheme(id = ThemeId(ThemeKey.of("dt2"), CatalogId(drift, TenantId(t.id))), name = "D2 (post-release)").execute()
            }
        }

        whenever {
            restTemplate.getForEntity("/tenants/${t.id}/catalogs", String::class.java)
        }

        then {
            val response = result<org.springframework.http.ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            val body = response.body!!
            // Drifted catalog: version + the pending-changes hint with the
            // "exports as v2.0.0-dev" title.
            assertThat(body).contains("pending changes")
            assertThat(body).contains("v2.0.0-dev")
            // Clean catalog's row carries no pending hint (only the drifted one).
            assertThat(body.split("pending changes")).hasSize(2) // exactly one occurrence
        }
    }
}

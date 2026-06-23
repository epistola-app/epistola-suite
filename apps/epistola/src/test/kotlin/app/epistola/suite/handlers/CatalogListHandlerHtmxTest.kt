package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity

/**
 * Server-side contract for the catalogs list after adding search + sort (#494). Catalogs
 * sort but do NOT paginate, so the load-bearing assertion is the exact, clean `HX-Push-Url`
 * (no `size`/`page`). Ordering/filtering assertions prove the sort + search actually drive
 * the rendered rows, and the full-page case proves the search box rehydrates on refresh.
 */
class CatalogListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedCatalogs(tenantName: String, names: List<Pair<String, String>>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        names.forEach { (slug, name) ->
            CreateCatalog(tenantKey = tenantId.key, id = CatalogKey.of(slug), name = name).execute()
        }
        tenantId
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    private fun postHtmx(url: String, currentUrl: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            set("HX-Request", "true")
            set("HX-Current-URL", currentUrl)
        }
        return restTemplate.exchange(url, HttpMethod.POST, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `default HTMX list pushes the clean canonical url with no size or page`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedCatalogs("Cat Default", listOf("aaa-cat" to "Aaa Cat")) }

        whenever { getHtmx("/tenants/${tenantId.key.value}/catalogs") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("id=\"catalog-list\"").contains("<table")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key.value}/catalogs?sort=name&dir=asc")
        }
    }

    @Test
    fun `sort by name ascending orders rows and flags the active ascending column`() = fixture {
        lateinit var tenantId: TenantId
        given {
            tenantId = seedCatalogs(
                "Cat Sort Asc",
                listOf("zzz-cat" to "Zzz Cat", "aaa-cat" to "Aaa Cat", "mmm-cat" to "Mmm Cat"),
            )
        }

        whenever { getHtmx("/tenants/${tenantId.key.value}/catalogs?sort=name&dir=asc") }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body.indexOf("Aaa Cat")).isLessThan(body.indexOf("Mmm Cat"))
            assertThat(body.indexOf("Mmm Cat")).isLessThan(body.indexOf("Zzz Cat"))
            assertThat(body).contains("is-sorted is-asc")
        }
    }

    @Test
    fun `the active descending column is flagged is-desc`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedCatalogs("Cat Sort Desc", listOf("aaa-cat" to "Aaa Cat")) }

        whenever { getHtmx("/tenants/${tenantId.key.value}/catalogs?sort=name&dir=desc") }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("is-sorted is-desc")
            assertThat(result<ResponseEntity<String>>().headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key.value}/catalogs?sort=name&dir=desc")
        }
    }

    @Test
    fun `search narrows rows and is carried in the pushed url`() = fixture {
        lateinit var tenantId: TenantId
        given {
            tenantId = seedCatalogs(
                "Cat Search",
                listOf("invoices-cat" to "Invoices Catalog", "reports-cat" to "Reports Catalog"),
            )
        }

        whenever { getHtmx("/tenants/${tenantId.key.value}/catalogs?q=Invoices") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.body).contains("Invoices Catalog").doesNotContain("Reports Catalog")
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Invoices")
        }
    }

    @Test
    fun `a search miss shows the search-aware empty state`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedCatalogs("Cat Empty", listOf("only-cat" to "Only Catalog")) }

        whenever { getHtmx("/tenants/${tenantId.key.value}/catalogs?q=zzzzz") }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("No catalogs match your search").doesNotContain("<table")
        }
    }

    @Test
    fun `a full-page load reflects the active search term in the search box`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedCatalogs("Cat Reflect", listOf("invoices-cat" to "Invoices Catalog")) }

        whenever { restTemplate.getForEntity("/tenants/${tenantId.key.value}/catalogs?q=Invoices", String::class.java) }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).containsPattern("search-input[^>]*value=\"Invoices\"")
        }
    }

    @Test
    fun `deleting a catalog re-renders honoring the search active in HX-Current-URL`() = fixture {
        lateinit var tenantId: TenantId
        given {
            tenantId = seedCatalogs(
                "Cat Del Search",
                listOf("keep-invoice" to "Keep Invoice", "other-report" to "Other Report", "temp-cat" to "Temp Cat"),
            )
        }

        // A mutation POSTs to a fixed URL; the active search rides only in HX-Current-URL.
        whenever {
            postHtmx(
                "/tenants/${tenantId.key.value}/catalogs/temp-cat/delete",
                "http://localhost/tenants/${tenantId.key.value}/catalogs?q=Invoice",
            )
        }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body).contains("Keep Invoice") // matches the active q
            assertThat(body).doesNotContain("Other Report") // filtered out, though it still exists
            assertThat(body).doesNotContain("Temp Cat") // the deleted row
        }
    }

    @Test
    fun `deleting a catalog re-renders honoring the sort active in HX-Current-URL`() = fixture {
        lateinit var tenantId: TenantId
        given {
            tenantId = seedCatalogs("Cat Del Sort", listOf("aaa-cat" to "Aaa Cat", "temp-cat" to "Temp Cat"))
        }

        whenever {
            postHtmx(
                "/tenants/${tenantId.key.value}/catalogs/temp-cat/delete",
                "http://localhost/tenants/${tenantId.key.value}/catalogs?sort=name&dir=desc",
            )
        }

        then {
            assertThat(result<ResponseEntity<String>>().body!!).contains("is-sorted is-desc")
        }
    }
}

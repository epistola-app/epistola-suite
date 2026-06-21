package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TestIdHelpers
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
 * Locks the templates list endpoint's server-side contract after merging list+search
 * into one paginated/sortable handler (#494). The load-bearing assertion is the exact
 * `HX-Push-Url` header: it proves the canonical URL is built on the server, so refresh
 * and bookmarking work. The fragment/ordering assertions prove sort/search/paging
 * actually drive the query.
 */
class DocumentTemplateListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedTemplates(tenantName: String, names: List<String>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        names.forEach { name ->
            CreateDocumentTemplate(
                id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)),
                name = name,
            ).execute()
        }
        tenantId
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `default HTMX list renders the data-table and pushes the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("Default List", listOf("Alpha", "Bravo")) }

        whenever { getHtmx("/tenants/${tenantId.key}/templates") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"data-table\"")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key}/templates?sort=updated&dir=desc&size=50&page=1")
        }
    }

    @Test
    fun `sort by name ascending orders rows and pushes the sort state`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("Sort Asc", listOf("Charlie", "Alpha", "Bravo")) }

        whenever { getHtmx("/tenants/${tenantId.key}/templates?sort=name&dir=asc") }

        then {
            val body = result<ResponseEntity<String>>().body!!
            assertThat(body.indexOf("Alpha")).isLessThan(body.indexOf("Bravo"))
            assertThat(body.indexOf("Bravo")).isLessThan(body.indexOf("Charlie"))
            // The active ascending column shows the up chevron.
            assertThat(body).contains("chevron-up")
            assertThat(result<ResponseEntity<String>>().headers.getFirst("HX-Push-Url"))
                .contains("sort=name&dir=asc")
        }
    }

    @Test
    fun `sort descending renders the down chevron on the active column`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("Sort Desc", listOf("Alpha", "Bravo")) }

        whenever { getHtmx("/tenants/${tenantId.key}/templates?sort=name&dir=desc") }

        then {
            assertThat(result<ResponseEntity<String>>().body).contains("chevron-down")
        }
    }

    @Test
    fun `an out-of-range page is clamped and the effective page is pushed`() = fixture {
        lateinit var tenantId: TenantId
        // 26 templates at size 25 => 2 pages. Requesting page 99 must land on page 2.
        given { tenantId = seedTemplates("Clamp", (1..26).map { "Template %02d".format(it) }) }

        whenever { getHtmx("/tenants/${tenantId.key}/templates?size=25&page=99") }

        then {
            val pushed = result<ResponseEntity<String>>().headers.getFirst("HX-Push-Url")
            assertThat(pushed).contains("size=25").contains("page=2")
        }
    }

    @Test
    fun `search preserves the active sort and size`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("Search", listOf("Invoice A", "Invoice B", "Report")) }

        whenever { getHtmx("/tenants/${tenantId.key}/templates?q=Invoice&sort=name&dir=asc&size=25") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.body).contains("Invoice A").contains("Invoice B").doesNotContain("Report")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .contains("q=Invoice").contains("sort=name&dir=asc").contains("size=25")
        }
    }

    @Test
    fun `an invalid page size falls back to the default`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("Bad Size", listOf("Alpha")) }

        whenever { getHtmx("/tenants/${tenantId.key}/templates?size=7") }

        then {
            assertThat(result<ResponseEntity<String>>().headers.getFirst("HX-Push-Url")).contains("size=50")
        }
    }

    @Test
    fun `the catalog filter narrows rows and is carried in the pushed url`() = fixture {
        data class Seed(val tenantId: TenantId, val catalog: String)
        lateinit var seed: Seed
        given {
            seed = withMediator {
                val tenantId = TenantId(createTenant("Catalog Filter").id)
                val catalogA = CatalogKey.of("cat-a")
                CreateCatalog(tenantKey = tenantId.key, id = catalogA, name = "Catalog A").execute()
                CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId(catalogA, tenantId)), name = "In Catalog A").execute()
                CreateDocumentTemplate(id = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId)), name = "In Default").execute()
                Seed(tenantId, catalogA.value)
            }
        }

        whenever { getHtmx("/tenants/${seed.tenantId.key}/templates?catalog=${seed.catalog}") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.body).contains("In Catalog A").doesNotContain("In Default")
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("catalog=cat-a")
        }
    }

    @Test
    fun `a non-HTMX request renders the full page`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("Full Page", listOf("Alpha")) }

        whenever { restTemplate.getForEntity("/tenants/${tenantId.key}/templates", String::class.java) }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"page-title\"").contains("data-testid=\"data-table\"")
        }
    }

    @Test
    fun `each row renders exactly once and not duplicated outside the table`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("No Dup Rows", listOf("Alpha", "Bravo", "Charlie")) }

        whenever { restTemplate.getForEntity("/tenants/${tenantId.key}/templates", String::class.java) }

        then {
            val body = result<ResponseEntity<String>>().body!!
            // If the `rows` fragment renders both inside the table AND inline, each row
            // appears twice (the stray copy flattens to loose cells below the table).
            val rowCount = Regex("data-testid=\"template-row\"").findAll(body).count()
            assertThat(rowCount).isEqualTo(3)
        }
    }

    @Test
    fun `the old search route is gone`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedTemplates("No Search Route", listOf("Alpha")) }

        whenever { restTemplate.getForEntity("/tenants/${tenantId.key}/templates/search", String::class.java) }

        then {
            assertThat(result<ResponseEntity<String>>().statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        }
    }
}

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
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
 * Locks the code-lists list endpoint's server-side contract after converting it to the shared
 * data-table (#494). CodeList carries an encrypted Secret + JOIN-aliased catalog_type, so this
 * also proves the paged query's `@Nested` row mapping reconstructs the full model. Search is new.
 */
class CodeListListHandlerHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun seedCodeLists(tenantName: String, slugsToNames: List<Pair<String, String>>): TenantId = withMediator {
        val tenantId = TenantId(createTenant(tenantName).id)
        slugsToNames.forEach { (slug, name) ->
            CreateCodeList(
                id = CodeListId(CodeListKey.of(slug), CatalogId.default(tenantId)),
                displayName = name,
                sourceType = CodeListSource.INLINE,
                entries = listOf(CodeListEntry("a", "Alpha")),
            ).execute()
        }
        tenantId
    }

    private fun getHtmx(url: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply { set("HX-Request", "true") }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    @Test
    fun `default HTMX list renders the data-table with mapped rows and pushes the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedCodeLists("CL Default", listOf("locales" to "Locales", "currencies" to "Currencies")) }

        whenever { getHtmx("/tenants/${tenantId.key}/code-lists") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
            assertThat(response.body).contains("data-testid=\"data-table\"")
            assertThat(response.body).contains("Locales").contains("Currencies")
            assertThat(response.headers.getFirst("HX-Push-Url"))
                .isEqualTo("/tenants/${tenantId.key}/code-lists?sort=name&dir=asc&size=10&page=1")
        }
    }

    @Test
    fun `search filters rows and carries the term into the canonical url`() = fixture {
        lateinit var tenantId: TenantId
        given { tenantId = seedCodeLists("CL Search", listOf("locales" to "Locales", "currencies" to "Currencies")) }

        whenever { getHtmx("/tenants/${tenantId.key}/code-lists?q=Local") }

        then {
            val response = result<ResponseEntity<String>>()
            assertThat(response.headers.getFirst("HX-Push-Url")).contains("q=Local")
            assertThat(response.body).contains("Locales").doesNotContain("Currencies")
        }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ExportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.testing.TestNamespaces
import app.epistola.suite.testing.testCatalog
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
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * Where a new catalog is named, chosen in its create dialog (ADR 0034, section 3), and the release
 * of a catalog in a namespace its tenant does not hold, withheld in words (section 2).
 */
class CatalogNamespaceHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun getHtmx(url: String): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    )

    private fun postForm(url: String, fields: Map<String, String>): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            set("HX-Request", "true")
        }
        val form = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return restTemplate.postForEntity(url, HttpEntity(form, headers), String::class.java)
    }

    @Test
    fun `the dialog preselects the one namespace held and offers local, and the catalog is named where chosen`() {
        val tenant = createTenant("Namespace Dialog").id
        val own = TestNamespaces.of(tenant)

        val dialog = getHtmx("/tenants/${tenant.value}/catalogs/new").body!!
        assertThat(dialog).contains("data-radio-panes=\"namespaceChoice\"")
        assertThat(Regex("""<input type="radio" name="namespaceChoice" value="${Regex.escape(own.toString())}"[^>]*checked""").containsMatchIn(dialog))
            .`as`("the one held is preselected").isTrue()
        assertThat(dialog).contains("data-testid=\"catalog-namespace-local\"").doesNotContain("catalog-namespace-none-held")

        postForm("/tenants/${tenant.value}/catalogs/create", mapOf("name" to "Held", "slug" to "held", "namespaceChoice" to own.toString()))
        postForm("/tenants/${tenant.value}/catalogs/create", mapOf("name" to "Notes", "slug" to "notes", "namespaceChoice" to "local", "localNamespace" to "personnel"))

        withMediator {
            assertThat(GetCatalog(testCatalog(tenant, CatalogKey.of("held"))).query()!!.let { it.registry to it.namespace }).isEqualTo(own.authority to own.namespace)
            assertThat(GetCatalog(testCatalog(tenant, CatalogKey.of("notes"))).query()!!.let { it.registry to it.namespace }).isEqualTo("local" to "personnel")
        }
    }

    @Test
    fun `a tenant holding no namespace is offered local, and a namespace it does not hold is refused in the form`() {
        val tenant = withMediator { CreateTenant(TenantKey.of("catalognamespacehtmx-bare"), "Namespace Bare").execute().id }

        val dialog = getHtmx("/tenants/${tenant.value}/catalogs/new").body!!
        assertThat(dialog).contains("This tenant holds no namespace yet; a platform administrator can grant one.")
        assertThat(Regex("""<input type="radio" name="namespaceChoice" value="local"[^>]*checked""").containsMatchIn(dialog)).`as`("local is preselected").isTrue()

        val refused = postForm("/tenants/${tenant.value}/catalogs/create", mapOf("name" to "Other", "slug" to "other", "namespaceChoice" to "gemeente-x.nl/acme"))
        assertThat(refused.statusCode.value()).isEqualTo(422)
        assertThat(refused.body!!).contains("This tenant does not hold the namespace acme (gemeente-x.nl); an administrator can grant it.")
    }

    @Test
    fun `a catalog in a namespace its tenant does not hold says why it cannot be published`() {
        val author = createTenant("Namespace Author").id
        val receiver = createTenant("Namespace Receiver").id
        val letters = CatalogKey.of("letters")
        withMediator {
            CreateCatalog(author, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(author), letters)), "Brand").execute()
            MarkCatalogReady(testCatalog(author, letters)).execute()
            ReleaseCatalogDraft(catalog = testCatalog(author, letters), version = "1.0.0").execute()
            ImportCatalogZip(receiver, ExportCatalogZip(testCatalog(author, letters), "1.0.0").execute().zipBytes).execute()
            CreateTheme(ThemeId(ThemeKey.of("ours"), testCatalog(TenantId(receiver), letters)), "Ours").execute()
            MarkCatalogReady(testCatalog(receiver, letters)).execute()
        }
        val sentence = TestNamespaces.of(author).let { "This tenant does not hold the namespace ${it.namespace} (${it.authority}); an administrator can grant it." }

        val draft = restTemplate.getForEntity("/tenants/${receiver.value}/catalogs/letters/draft", String::class.java).body!!
        assertThat(draft).contains("data-testid=\"catalog-release-blocked-namespace\"").contains(sentence)
        assertThat(draft).doesNotContain("data-testid=\"catalog-release-open\"")

        val dialog = getHtmx("/tenants/${receiver.value}/catalogs/letters/release").body!!
        assertThat(dialog).contains("data-testid=\"release-blocked-namespace\"").contains(sentence)
    }
}

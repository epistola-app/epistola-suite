// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.testing.TestNamespaces
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
 * A catalog's UI address names its namespace, `/tenants/{t}/catalogs/{namespace}/{key}/…` (ADR 0034,
 * phase 4), so two catalogs called `letters` each have their own pages; an address by key alone
 * keeps working while the key names one catalog, and is refused, naming them, when it names two.
 */
class CatalogAddressHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    private fun get(url: String, htmx: Boolean = false): ResponseEntity<String> = restTemplate.exchange(
        url,
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { if (htmx) set("HX-Request", "true") }),
        String::class.java,
    )

    @Test
    fun `two letters each have pages of their own, and the key alone is refused naming both`() {
        val tenant = createTenant("Catalog Address").id
        withMediator {
            TestNamespaces.grant(tenant, "acme").execute()
            TestNamespaces.grant(tenant, "bureau").execute()
            CreateCatalog(tenant, letters, "Acme letters", namespace = "acme").execute()
            CreateCatalog(tenant, letters, "Bureau letters", namespace = "bureau").execute()
            CatalogNames.within(letters, "acme") { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Acme invoice").execute() }
            CatalogNames.within(letters, "bureau") { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Bureau invoice").execute() }
        }

        val list = get("/tenants/${tenant.value}/catalogs").body!!
        assertThat(list).contains("/tenants/${tenant.value}/catalogs/acme/letters/browse", "/tenants/${tenant.value}/catalogs/bureau/letters/browse")

        for ((namespace, title) in listOf("acme" to "Acme invoice", "bureau" to "Bureau invoice")) {
            val templates = get("/tenants/${tenant.value}/catalogs/$namespace/letters/templates")
            assertThat(templates.statusCode).`as`(namespace).isEqualTo(HttpStatus.OK)
            assertThat(templates.body!!).contains(title).doesNotContain(if (namespace == "acme") "Bureau invoice" else "Acme invoice")
            // What the page links to names the catalog the same way.
            assertThat(templates.body!!).contains("/tenants/${tenant.value}/catalogs/$namespace/letters/").doesNotContain("/tenants/${tenant.value}/catalogs/letters/")
        }

        val byKey = get("/tenants/${tenant.value}/catalogs/letters/templates", htmx = true)
        assertThat(byKey.statusCode.is4xxClientError).`as`(byKey.body).isTrue()
        assertThat(byKey.body!!).contains("names several catalogs of this tenant (acme/letters, bureau/letters)")
    }

    @Test
    fun `an address by key alone still opens the one catalog with that key, and its namespaced address the same`() {
        val tenant = createTenant("Catalog Address Single").id
        withMediator { CreateCatalog(tenant, letters, "Letters").execute() }
        val namespace = TestNamespaces.of(tenant).namespace

        val opened = get("/tenants/${tenant.value}/catalogs/letters/browse")
        assertThat(opened.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(opened.body!!).contains("/tenants/${tenant.value}/catalogs/letters/")

        val named = get("/tenants/${tenant.value}/catalogs/$namespace/letters/browse")
        assertThat(named.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(named.body!!).contains("/tenants/${tenant.value}/catalogs/$namespace/letters/").doesNotContain("/tenants/${tenant.value}/catalogs/letters/")
        // An HTMX request by key alone is served where it is.
        assertThat(get("/tenants/${tenant.value}/catalogs/letters/templates", htmx = true).statusCode).isEqualTo(HttpStatus.OK)
    }
}

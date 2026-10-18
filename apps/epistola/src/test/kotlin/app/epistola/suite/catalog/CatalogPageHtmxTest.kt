// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * The catalog page and a draft's own page (docs/catalog-workspace-navigation.md): from a new catalog
 * to a document an application makes (use case 1), and the next version people keep working in
 * (use case 2), with every version reachable from Versions.
 */
class CatalogPageHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    private fun catalog(name: String, setup: (Tenant, CatalogId) -> Unit = { _, _ -> }): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
                    setup(tenant, CatalogId(letters, TenantId(tenant.id)))
                }
            }
        }
        return tenant
    }

    private fun invoice(catalog: CatalogId) {
        CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), catalog), "Invoice").execute().withRequiredDataExample()
    }

    @Test
    fun `a new catalog says to start with a template, and offers it, not publishing nothing`() {
        val tenant = catalog("Page New")

        val status = status(get(tenant, "/catalogs/letters/browse").body!!)

        assertThat(status).contains("Start with a template.")
        assertThat(status).contains("data-testid=\"catalog-status-new-template\"").contains("hx-get=\"/tenants/${tenant.id}/templates/new?catalog=letters\"")
        assertThat(status).doesNotContain("data-testid=\"catalog-release-open\"")
        assertThat(status).contains("Not deployed anywhere yet.").contains("data-testid=\"catalog-step-no-environment\"")
    }

    @Test
    fun `with a template the box offers Publish, and once published says where it is live and how to make it live`() {
        val tenant = catalog("Page Steps") { tenant, catalog ->
            invoice(catalog)
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), "Test").execute()
        }

        val unpublished = status(get(tenant, "/catalogs/letters/browse").body!!)
        assertThat(unpublished).contains("Not published yet.").contains("data-testid=\"catalog-release-open\"")
        assertThat(unpublished).doesNotContain("data-testid=\"catalog-status-make-live\"")

        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant.id))) }

        val published = get(tenant, "/catalogs/letters/browse").body!!
        assertThat(status(published)).contains("Everything is published, as v1.0.0.").doesNotContain("data-testid=\"catalog-release-open\"")
        assertThat(status(published)).contains("Not deployed anywhere yet.").contains("href=\"/tenants/${tenant.id}/catalogs/letters/deployments\"")
        assertThat(get(tenant, "/catalogs/letters/deployments").body!!).contains("id=\"catalog-deployments\"")
        assertThat(get(tenant, "/catalogs/letters/versions").body!!).contains("href=\"/tenants/${tenant.id}/catalogs/letters/releases/1.0.0\"")
    }

    @Test
    fun `once live the box says where, and what an application asks for`() {
        val tenant = catalog("Page Live") { tenant, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), "Test").execute()
            DeployRelease(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), letters, "1.0.0", includeDependencies = true).execute()
        }

        val status = status(get(tenant, "/catalogs/letters/browse").body!!)

        assertThat(status).contains("Test (v1.0.0)")
        assertThat(status).contains("catalog <code>letters</code>").contains("template <code>invoice</code>").contains("environment <code>test</code>")
    }

    @Test
    fun `the overview counts each kind with what is not published yet, each a link to its list`() {
        val tenant = catalog("Page Contents") { _, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
            CreateDocumentTemplate(TemplateId(TemplateKey.of("reminder"), catalog), "Reminder").execute().withRequiredDataExample()
        }

        val page = get(tenant, "/catalogs/letters/browse").body!!

        // What the catalog is comes first.
        assertThat(page.indexOf("data-testid=\"catalog-about\"")).isLessThan(page.indexOf("data-testid=\"catalog-status\""))
        val templates = page.substringAfter("data-testid=\"catalog-count\" data-type=\"template\"").substringBefore("</a>")
        assertThat(templates).contains(">2<").contains(">Templates<").contains("1 not published yet")
        assertThat(page).contains("href=\"/tenants/${tenant.id}/templates?catalog=letters\" class=\"ep-panel stat-card\"")
        assertThat(page.substringAfter("data-type=\"stencil\"").substringBefore("</a>")).contains(">0<").doesNotContain("not published")
        assertThat(page).`as`("templates are counted, not listed").doesNotContain("data-testid=\"catalog-templates\"")
    }

    @Test
    fun `the next version has its own page with what is not published yet and its templates, its details a page of their own`() {
        val tenant = catalog("Page Draft") { _, catalog -> invoice(catalog) }

        val page = get(tenant, "/catalogs/letters/draft")

        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        val body = page.body!!
        assertThat(body).contains("<span id=\"page-title-text\">Next version</span>")
        assertThat(body).contains("data-testid=\"catalog-next-release\"").contains("<h2>Not published yet</h2>")
        assertThat(body).contains("href=\"/tenants/${tenant.id}/templates/letters/invoice\"")
        assertThat(body).contains("href=\"/tenants/${tenant.id}/catalogs/letters/settings\"")
        assertThat(get(tenant, "/catalogs/letters/settings").body!!).contains("id=\"catalog-metadata\"")
    }

    @Test
    fun `long-term work in the catalog page's address goes to its own page`() {
        lateinit var work: java.util.UUID
        val tenant = catalog("Page Redirects") { tenant, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
            work = requireNotNull(CreateCatalogDraft(tenant.id, CatalogKey.of("letters"), "Redesign").execute().draftId)
        }

        assertThat(get(tenant, "/catalogs/letters@$work/browse").headers.location.toString())
            .isEqualTo("/tenants/${tenant.id}/catalogs/letters@$work/draft")
    }

    @Test
    fun `each New on the catalog page opens its dialog on that catalog, not on the one that sorts first`() {
        val tenant = catalog("Page New Dialogs") { tenant, _ ->
            CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("aardvark"), name = "Aardvark").execute()
        }

        listOf("templates", "themes", "stencils", "images", "fonts", "code-lists", "attributes").forEach { area ->
            val dialog = restTemplate.exchange(
                "/tenants/${tenant.id}/$area/new?catalog=letters",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
                String::class.java,
            ).body!!
            assertThat(dialog).`as`("$area creates in the catalog it was opened on").contains("type=\"hidden\" name=\"catalog\" value=\"letters\"")
        }
    }

    /**
     * The catalog's nav leads to each kind's list filtered to it, plainly (`?catalog=letters`); the
     * list's New must create in that catalog, not in the one that sorts first.
     */
    @Test
    fun `a part list opened from the catalog's nav creates in that catalog`() {
        val tenant = catalog("Page List New") { tenant, _ ->
            CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("aardvark"), name = "Aardvark").execute()
        }

        listOf("stencils", "themes", "images", "fonts", "code-lists").forEach { area ->
            val list = get(tenant, "/$area?catalog=letters").body!!
            assertThat(list).`as`("$area New carries the catalog").contains("hx-get=\"/tenants/${tenant.id}/$area/new?catalog=letters\"")
        }
    }

    private fun status(page: String): String = page.substringAfter("data-testid=\"catalog-status\"").substringBefore("data-testid=\"catalog-counts\"")

    private fun get(tenant: Tenant, path: String) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)
        .getForEntity("/tenants/${tenant.id}$path", String::class.java)
}

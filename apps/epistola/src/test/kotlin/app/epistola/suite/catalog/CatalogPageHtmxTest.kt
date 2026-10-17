// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
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
        assertThat(status).contains("Not live anywhere yet.").contains("data-testid=\"catalog-step-no-environment\"")
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
        assertThat(status(published)).contains("Not live anywhere yet.").contains("href=\"#catalog-deployments\"")
        assertThat(published).contains("id=\"catalog-deployments\"")
        assertThat(published).contains("href=\"/tenants/${tenant.id}/catalogs/letters/releases/1.0.0\"")
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
    fun `templates come first and say what is not published yet, the other kinds below with one line to add the rest`() {
        val tenant = catalog("Page Contents") { _, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
            CreateDocumentTemplate(TemplateId(TemplateKey.of("reminder"), catalog), "Reminder").execute().withRequiredDataExample()
        }

        val page = get(tenant, "/catalogs/letters/browse").body!!

        val templates = page.substringAfter("data-testid=\"catalog-templates\"").substringBefore("data-testid=\"catalog-contents\"")
        assertThat(templates).contains("href=\"/tenants/${tenant.id}/templates/letters/invoice\"")
        assertThat(templates.substringAfter("data-slug=\"reminder\"")).contains(">New<")
        assertThat(templates.substringAfter("data-slug=\"invoice\"").substringBefore("data-slug=\"reminder\"")).doesNotContain(">New<").doesNotContain(">Changed<")
        assertThat(templates).contains("hx-get=\"/tenants/${tenant.id}/templates/new?catalog=letters\"")
        val add = page.substringAfter("data-testid=\"catalog-contents-add\"").substringBefore("</div>")
        assertThat(add).contains("hx-get=\"/tenants/${tenant.id}/stencils/new?catalog=letters\"")
    }

    @Test
    fun `the next version has its own page with what is not published yet, its contents and its details`() {
        val tenant = catalog("Page Draft") { _, catalog -> invoice(catalog) }

        val page = get(tenant, "/catalogs/letters/draft")

        assertThat(page.statusCode).isEqualTo(HttpStatus.OK)
        val body = page.body!!
        assertThat(body).contains("<span id=\"page-title-text\">Next version</span>")
        assertThat(body).contains("data-testid=\"catalog-next-release\"").contains("<h2>Not published yet</h2>")
        assertThat(body).contains("href=\"/tenants/${tenant.id}/templates/letters/invoice\"")
        assertThat(body).contains("id=\"catalog-metadata\"")
    }

    @Test
    fun `a version in the catalog page's address goes to that version's own page`() {
        val tenant = catalog("Page Redirects") { _, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
        }

        assertThat(get(tenant, "/catalogs/letters@1.0.1/browse").headers.location.toString())
            .isEqualTo("/tenants/${tenant.id}/catalogs/letters@1.0.1/draft")
        assertThat(get(tenant, "/catalogs/letters@1.0.0/draft").headers.location.toString())
            .isEqualTo("/tenants/${tenant.id}/catalogs/letters/releases/1.0.0")
    }

    private fun status(page: String): String = page.substringAfter("data-testid=\"catalog-status\"").substringBefore("data-testid=\"catalog-templates\"")

    private fun get(tenant: Tenant, path: String) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)
        .getForEntity("/tenants/${tenant.id}$path", String::class.java)
}

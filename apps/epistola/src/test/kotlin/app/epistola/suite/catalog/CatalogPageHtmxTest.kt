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
    fun `a new catalog starts with adding a template, and its next version becomes the first version`() {
        val tenant = catalog("Page New")

        val page = get(tenant, "/catalogs/letters/browse").body!!

        assertThat(page).contains("data-testid=\"catalog-getting-started\"")
        assertThat(step(page, 1)).contains("data-done=\"false\"").contains("/tenants/${tenant.id}/templates/new?catalog=letters")
        assertThat(page).contains("Nothing is published yet: this becomes the first version.")
        assertThat(page.substringAfter("data-testid=\"catalog-versions\"")).contains(">Next version<")
    }

    @Test
    fun `with a template the next step is publishing and making it live, in an environment there has to be first`() {
        val tenant = catalog("Page Steps") { _, catalog -> invoice(catalog) }

        val unpublished = get(tenant, "/catalogs/letters/browse").body!!
        assertThat(step(unpublished, 1)).contains("data-done=\"true\"")
        assertThat(step(unpublished, 2)).contains("data-testid=\"catalog-step-publish\"")
        assertThat(step(unpublished, 3)).contains("template <code>invoice</code>")

        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant.id))) }

        val published = get(tenant, "/catalogs/letters/browse").body!!
        assertThat(step(published, 2)).`as`("published, not live yet").contains("data-done=\"false\"")
            .contains("data-testid=\"catalog-step-no-environment\"")
        assertThat(published).contains("href=\"/tenants/${tenant.id}/catalogs/letters/releases/1.0.0\"")
    }

    @Test
    fun `once something is live the steps make way, and the next version says what it is based on`() {
        val tenant = catalog("Page Live") { tenant, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), "Test").execute()
            DeployRelease(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), letters, "1.0.0", includeDependencies = true).execute()
        }

        val page = get(tenant, "/catalogs/letters/browse").body!!

        assertThat(page).doesNotContain("data-testid=\"catalog-getting-started\"")
        assertThat(page).contains("Based on v1.0.0, the latest published version.")
        assertThat(page).contains("No changes yet.")
        val published = page.substringAfter("data-version=\"1.0.0\"").substringBefore("</tr>")
        assertThat(published).contains("Published").contains("test")
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

    @Test
    fun `the catalog page shows what the next version holds, grouped by kind, with a New for each kind on this catalog`() {
        val tenant = catalog("Page Contents") { _, catalog -> invoice(catalog) }

        val contents = get(tenant, "/catalogs/letters/browse").body!!.substringAfter("data-testid=\"catalog-contents\"")

        assertThat(contents).contains("<h2>In the next version</h2>")
        val templates = contents.substringAfter("data-type=\"template\"").substringBefore("data-testid=\"catalog-contents-add\"")
        assertThat(templates).contains("href=\"/tenants/${tenant.id}/templates/letters/invoice\"").contains(">Invoice<")
        assertThat(templates).contains("hx-get=\"/tenants/${tenant.id}/templates/new?catalog=letters\"")
        // A kind with nothing yet is one button on the "add" line, not a box of its own.
        val add = contents.substringAfter("data-testid=\"catalog-contents-add\"").substringBefore("</div>")
        assertThat(add).contains("Add something else:").contains("hx-get=\"/tenants/${tenant.id}/stencils/new?catalog=letters\"")
        assertThat(contents.substringBefore("data-testid=\"catalog-contents-add\"")).doesNotContain("data-type=\"stencil\"")
    }

    private fun step(page: String, number: Int): String = page.substringAfter("data-step=\"$number\"").substringBefore("</li>")

    private fun get(tenant: Tenant, path: String) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)
        .getForEntity("/tenants/${tenant.id}$path", String::class.java)
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogVersion
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
        assertThat(status).contains("data-testid=\"catalog-status-new-template\"").contains("hx-get=\"/tenants/${tenant.id}/catalogs/letters/templates/new\"")
        assertThat(status).doesNotContain("data-testid=\"catalog-release-open\"")
        assertThat(status).contains("data-testid=\"catalog-step-no-environment\"")
    }

    @Test
    fun `with a template the box offers Publish, and once published says where it is live and how to make it live`() {
        val tenant = catalog("Page Steps") { tenant, catalog ->
            invoice(catalog)
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), "Test").execute()
        }

        val unpublished = status(get(tenant, "/catalogs/letters/browse").body!!)
        assertThat(unpublished).contains("Not published yet.").contains("data-testid=\"catalog-release-open\"")
        assertThat(unpublished).`as`("the names say what they are").contains("In it: Invoice")
        assertThat(unpublished).doesNotContain("data-testid=\"catalog-status-make-live\"")

        withMediator { mediator.releaseNext(CatalogId(letters, TenantId(tenant.id))) }

        val published = get(tenant, "/catalogs/letters/browse").body!!
        assertThat(status(published)).contains("Everything is published, as v1.0.0.").doesNotContain("data-testid=\"catalog-release-open\"")
        assertThat(status(published)).contains("Not deployed").contains("The latest published version is v1.0.0.").contains("href=\"/tenants/${tenant.id}/catalogs/letters/deployments\"")
        assertThat(get(tenant, "/catalogs/letters/deployments").body!!).contains("id=\"catalog-deployments\"")
        assertThat(get(tenant, "/catalogs/letters/versions").body!!).contains("href=\"/tenants/${tenant.id}/catalogs/letters/releases/1.0.0\"")
    }

    @Test
    fun `once live the box says where, and what an application asks for`() {
        val tenant = catalog("Page Live") { tenant, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), "Test").execute()
            DeployCatalogVersion(EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id)), letters, "1.0.0", includeDependencies = true).execute()
        }

        val status = status(get(tenant, "/catalogs/letters/browse").body!!)

        assertThat(status.substringAfter("data-environment=\"test\"")).contains(">v1.0.0<")
        assertThat(status).contains("catalog <code>letters</code>").contains("template <code>invoice</code>").contains("environment <code>test</code>")
        val namespace = withMediator { app.epistola.suite.catalog.queries.GetCatalog(tenant.id, letters).query()!!.namespace }
        assertThat(status).`as`("the API refuses a request without it").contains("namespace <code>$namespace</code>")
    }

    @Test
    fun `a production environment is marked unless its name says so already`() {
        val tenant = catalog("Page Production Mark") { tenant, _ ->
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production", production = true).execute()
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("live"), TenantId(tenant.id)), "Live", production = true).execute()
        }

        val page = get(tenant, "/catalogs/letters/browse").body!!

        fun box(key: String) = page.substringAfter("data-environment=\"$key\"").substringBefore("catalog-box\"")
        assertThat(box("production")).`as`("not \"Production Production\"").doesNotContain("catalog-environment-production")
        assertThat(box("live")).contains("catalog-environment-production")
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
        assertThat(page).contains("href=\"/tenants/${tenant.id}/catalogs/letters/templates\" class=\"ep-panel stat-card\"")
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
        assertThat(body).contains("href=\"/tenants/${tenant.id}/catalogs/letters/templates/invoice\"")
        assertThat(body).contains("href=\"/tenants/${tenant.id}/catalogs/letters/settings\"")
        assertThat(get(tenant, "/catalogs/letters/settings").body!!).contains("id=\"catalog-metadata\"")
    }

    @Test
    fun `parallel work in the catalog page's address goes to its own page`() {
        lateinit var work: String
        val tenant = catalog("Page Redirects") { tenant, catalog ->
            invoice(catalog)
            mediator.releaseNext(catalog)
            work = requireNotNull(CreateCatalogDraft(tenant.id, CatalogKey.of("letters"), "Redesign").execute().versionId)
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
                "/tenants/${tenant.id}/catalogs/letters/$area/new",
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
                String::class.java,
            ).body!!
            assertThat(dialog).`as`("$area creates in the catalog it was opened on").contains("/catalogs/letters/$area\"")
        }
    }

    /**
     * The catalog's nav leads to each kind's list in it (`/catalogs/letters/stencils`); the
     * list's New must create in that catalog, not in the one that sorts first.
     */
    @Test
    fun `a part list opened from the catalog's nav creates in that catalog`() {
        val tenant = catalog("Page List New") { tenant, _ ->
            CreateCatalog(tenantKey = tenant.id, id = CatalogKey.of("aardvark"), name = "Aardvark").execute()
        }

        listOf("stencils", "themes", "images", "fonts", "code-lists").forEach { area ->
            val list = get(tenant, "/catalogs/letters/$area").body!!
            assertThat(list).`as`("$area New carries the catalog").contains("hx-get=\"/tenants/${tenant.id}/catalogs/letters/$area/new\"")
        }
    }

    private fun status(page: String): String = page.substringAfter("data-testid=\"catalog-status\"").substringBefore("data-testid=\"catalog-counts\"")

    private fun get(tenant: Tenant, path: String) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)
        .getForEntity("/tenants/${tenant.id}$path", String::class.java)

    @Test
    fun `the details page deletes the catalog, except the system catalog`() {
        val tenant = catalog("Catalog Delete From Details")

        val lettersPage = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/settings", String::class.java).body!!
        assertThat(lettersPage).contains("data-testid=\"catalog-delete-form\"").contains("action=\"/tenants/${tenant.id}/catalogs/letters/delete\"")

        val system = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/system/settings", String::class.java).body!!
        assertThat(system).contains("data-testid=\"catalog-delete-section\"").doesNotContain("data-testid=\"catalog-delete-form\"")
            .contains("The system catalog cannot be deleted")

        // The form posts plainly: deleted, the browser lands on the list of catalogs.
        val deleted = restTemplate.postForEntity("/tenants/${tenant.id}/catalogs/letters/delete", null, String::class.java)
        assertThat(deleted.statusCode.value()).`as`(deleted.body).isLessThan(400)
        assertThat(deleted.body).contains("id=\"catalog-list\"")
        assertThat(withMediator { GetCatalog(tenant.id, letters).query() }).isNull()
    }

    @Test
    fun `the editor is given the presets the catalog declares`() {
        val tenant = catalog("Editor Presets") { t, c ->
            invoice(c)
            app.epistola.suite.catalog.versions.SetCatalogPresets(
                t.id,
                letters,
                listOf(app.epistola.suite.catalog.versions.CatalogPreset("heading-accent", "Heading accent")),
            ).execute()
        }

        val editor = restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/templates/invoice/variants/initial/editor", String::class.java).body!!

        assertThat(editor).contains("availablePresets").contains("heading-accent").contains("Heading accent")
    }

    @Test
    fun `the themes page sets the catalog's default theme and presets`() {
        val tenant = catalog("Catalog Theme Settings") { t, c ->
            app.epistola.suite.themes.commands.CreateTheme(
                app.epistola.suite.common.ids.ThemeId(app.epistola.suite.common.ids.ThemeKey.of("brand"), c),
                "Brand",
            ).execute()
        }
        // The section loads on the catalog's Themes page, next to the themes, and no longer on Details.
        assertThat(restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/themes", String::class.java).body!!)
            .contains("data-testid=\"catalog-theme-settings-mount\"")
            .contains("/tenants/${tenant.id}/catalogs/letters/theme-settings")
        assertThat(restTemplate.getForEntity("/tenants/${tenant.id}/catalogs/letters/settings", String::class.java).body!!)
            .doesNotContain("catalog-theme-settings-mount")
        val section = restTemplate.exchange(
            "/tenants/${tenant.id}/catalogs/letters/theme-settings",
            org.springframework.http.HttpMethod.GET,
            org.springframework.http.HttpEntity<Void>(org.springframework.http.HttpHeaders().apply { add("HX-Request", "true") }),
            String::class.java,
        ).body!!
        assertThat(section).contains("data-testid=\"catalog-theme-settings-form\"").contains("value=\"letters/brand\"")

        val form = org.springframework.util.LinkedMultiValueMap<String, String>().apply {
            add("defaultTheme", "letters/brand")
            add("presetKey", "heading-accent")
            add("presetName", "Heading accent")
            add("presetDescription", "")
        }
        val headers = org.springframework.http.HttpHeaders().apply {
            add("HX-Request", "true")
            contentType = org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED
        }
        val saved = restTemplate.exchange(
            "/tenants/${tenant.id}/catalogs/letters/theme-settings",
            org.springframework.http.HttpMethod.POST,
            org.springframework.http.HttpEntity(form, headers),
            String::class.java,
        ).body!!

        assertThat(saved).contains("data-testid=\"catalog-theme-settings-saved\"")
        val settings = withMediator { app.epistola.suite.catalog.versions.GetCatalogThemeSettings(tenant.id, letters).query() }
        assertThat(settings.defaultTheme?.themeKey?.value).isEqualTo("brand")
        assertThat(settings.presets.map { it.key }).containsExactly("heading-accent")
    }
}

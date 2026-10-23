// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Working inside a catalog (docs/workspace-shell.md): its lists are its own, with what the catalogs
 * it depends on hold below; creating there creates there; its Dependencies page says both sides and
 * lets you change them; its overview says what it is; its versions say what each started from.
 */
class CatalogWorkspacePagesHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val shared = CatalogKey.of("shared")
    private val letters = CatalogKey.of("letters")

    /** Shared (Footer stencil, Brand theme) published as 1.0.0; Letters' Invoice uses Brand, and declares Shared. */
    private fun seed(name: String): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    CreateCatalog(tenant.id, shared, "Shared").execute()
                    CreateCatalog(tenant.id, letters, "Letters").execute()
                    val sharedId = CatalogId(shared, TenantId(tenant.id))
                    CreateStencil(StencilId(StencilKey.of("footer"), sharedId), "Footer").execute()
                    CreateTheme(ThemeId(ThemeKey.of("brand"), sharedId), "Brand").execute()
                    MarkCatalogReady(tenant.id, shared).execute()
                    ReleaseCatalogDraft(tenant.id, shared, "1.0.0").execute()
                    val invoice = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant.id)))
                    CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
                    UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
                    // Using Shared named its next version; Letters pins Shared's release instead.
                    val target = GetCatalog(tenant.id, shared).query()!!
                    UpdateCatalogDependencies(tenant.id, letters, listOf(DeclaredCatalogDependency(target.registry, target.namespace, shared.value, releaseVersion = "1.0.0"))).execute()
                }
            }
        }
        return tenant
    }

    @Test
    fun `a list inside a catalog has no catalog to choose, and shows what the catalogs it depends on hold`() {
        val tenant = seed("Workspace Lists")

        val stencils = get(tenant, "/catalogs/letters/stencils")
        assertThat(stencils).doesNotContain("id=\"catalog-filter\"")
        val others = stencils.substringAfter("data-testid=\"from-other-catalogs\"")
        assertThat(others).contains(">Footer<").contains("In Shared").contains("href=\"/tenants/${tenant.id}/catalogs/shared/stencils/footer\"")

        assertThat(get(tenant, "/catalogs/letters/themes").substringAfter("data-testid=\"from-other-catalogs\"")).contains(">Brand<")
        assertThat(restTemplate.getForEntity("/tenants/${tenant.id}/stencils", String::class.java).statusCode.value())
            .`as`("there is no list across catalogs").isEqualTo(404)
        assertThat(get(tenant, "/catalogs/shared/stencils")).`as`("Shared depends on nothing").doesNotContain("data-testid=\"from-other-catalogs\"")
    }

    @Test
    fun `a create dialog opened inside a catalog creates there, with no catalog to choose`() {
        val tenant = seed("Workspace Create")

        listOf("templates", "themes", "stencils", "images", "fonts", "code-lists", "attributes").forEach { area ->
            val dialog = htmx(tenant, "/catalogs/letters/$area/new")
            assertThat(dialog).`as`(area).contains("/catalogs/letters/$area\"").doesNotContain("<select id=\"catalog\"")
        }
        assertThat(restTemplate.getForEntity("/tenants/${tenant.id}/stencils/new", String::class.java).statusCode.value())
            .`as`("creating is the catalog's: there is no create outside one").isEqualTo(404)
    }

    /**
     * Where people add a dependency, its "Depends on" table names it as the content does (ADR 0034),
     * in the same column, with the same header, as the draft's Dependencies table.
     */
    @Test
    fun `the Dependencies page shows the name the content uses for each catalog it depends on`() {
        val tenant = seed("Workspace Dependency Alias")

        val table = get(tenant, "/catalogs/letters/dependencies").substringAfter("data-testid=\"catalog-borrowed\"").substringBefore("</table>")

        assertThat(table).contains("<th title=\"What the templates, themes and stencils of this catalog call it\">Named in content as</th>")
        val row = table.substringAfter("data-testid=\"catalog-borrowed-catalog\" data-catalog=\"shared\"").substringBefore("</tr>")
        assertThat(row.substringAfter("data-testid=\"catalog-borrowed-alias\"").substringBefore("</td>")).isEqualTo("><code>shared</code>")
    }

    @Test
    fun `the Dependencies page says what a catalog depends on and what depends on it, and changing them lands back there`() {
        val tenant = seed("Workspace Dependencies")

        val letterPage = get(tenant, "/catalogs/letters/dependencies")
        assertThat(letterPage.substringAfter("data-testid=\"catalog-borrowed\"")).contains("data-catalog=\"shared\"")
        assertThat(letterPage).contains("data-testid=\"catalog-dependencies-edit\"").contains("hx-get=\"/tenants/${tenant.id}/catalogs/letters/dependencies/edit\"")

        val sharedPage = get(tenant, "/catalogs/shared/dependencies")
        assertThat(sharedPage.substringAfter("data-testid=\"catalog-dependents\"")).contains(">Letters<").contains("v1.0.0")
        assertThat(sharedPage).contains("Shared depends on no other catalog yet.")

        // The dialog names each catalog by its full name; take Shared's from it.
        val sharedOption = Regex("""value="([^"]*/shared)"""").find(htmx(tenant, "/catalogs/letters/dependencies/edit"))!!.groupValues[1]
        val saved = restTemplate.exchange(
            "/tenants/${tenant.id}/catalogs/letters/dependencies",
            HttpMethod.POST,
            HttpEntity(
                LinkedMultiValueMap<String, String>().apply {
                    add("catalog", sharedOption)
                    add("version", "")
                },
                HttpHeaders().apply {
                    contentType = MediaType.APPLICATION_FORM_URLENCODED
                    set("HX-Request", "true")
                },
            ),
            String::class.java,
        )
        assertThat(saved.headers.getFirst("HX-Location").orEmpty()).contains("/tenants/${tenant.id}/catalogs/letters/dependencies")
    }

    @Test
    fun `the overview says what the catalog is, with the way to its settings`() {
        val tenant = seed("Workspace About")
        withMediator {
            UpdateCatalogMetadata(tenantKey = tenant.id, catalogKey = letters, name = "Letters", description = "Letters to customers", attributes = emptyList()).execute()
        }

        // The description under the title, the key just below it, Settings beside it.
        val about = get(tenant, "/catalogs/letters/browse").substringAfter("<main").substringBefore("data-testid=\"catalog-status\"")
        assertThat(about).contains("Letters to customers").contains("/letters</code>")
            .contains("href=\"/tenants/${tenant.id}/catalogs/letters/settings\"")
    }

    @Test
    fun `every version says what it started from`() {
        val tenant = seed("Workspace Versions")

        val versions = get(tenant, "/catalogs/shared/releases")
        val published = versions.substringAfter("data-version=\"1.0.0\"").substringBefore("</tr>")
        assertThat(published.substringAfter("data-testid=\"catalog-version-base\"")).contains("Started from nothing")
        val next = versions.substringAfter("data-status=\"draft\"").substringBefore("</tr>")
        assertThat(next.substringAfter("data-testid=\"catalog-version-base\"").substringBefore("</td>")).contains(">v1.0.0<")
    }

    @Test
    fun `every version can be removed from Releases, a draft discarded and a published version deleted, landing back there`() {
        // Discarding the next version throws its changes away; it is opened again on the latest release.
        val tenant = seed("Workspace Remove")

        val versions = get(tenant, "/catalogs/shared/releases")
        assertThat(versions.substringAfter("data-status=\"draft\"").substringBefore("</tr>"))
            .contains("action=\"/tenants/${tenant.id}/catalogs/shared/drafts/discard\"").contains(">Discard<")
        assertThat(versions.substringAfter("data-version=\"1.0.0\"").substringBefore("</tr>"))
            .contains("action=\"/tenants/${tenant.id}/catalogs/shared/releases/1.0.0/delete\"").contains(">Delete<")

        val discarded = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).postForEntity(
            "/tenants/${tenant.id}/catalogs/shared/drafts/discard",
            HttpEntity(
                LinkedMultiValueMap<String, String>().apply { add("from", "versions") },
                HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED },
            ),
            String::class.java,
        )
        assertThat(discarded.headers.location.toString()).isEqualTo("/tenants/${tenant.id}/catalogs/shared/releases")
        // The next version is not gone: it starts again on the latest published version.
        assertThat(get(tenant, "/catalogs/shared/releases").substringAfter("data-status=\"draft\"").substringBefore("</tr>"))
            .contains(">Next release<").contains(">v1.0.0<")
    }

    @Test
    fun `a resource's back link returns to its catalog's list, and a published release's to Releases`() {
        val tenant = seed("Workspace Back")

        fun back(path: String) = Regex("""href="([^"]+)"\s+class="back-link"""").find(get(tenant, path))!!.groupValues[1]

        assertThat(back("/catalogs/letters/templates/invoice")).isEqualTo("/tenants/${tenant.id}/catalogs/letters/templates")
        assertThat(back("/catalogs/shared/stencils/footer")).isEqualTo("/tenants/${tenant.id}/catalogs/shared/stencils")
        assertThat(back("/catalogs/shared/themes/brand")).isEqualTo("/tenants/${tenant.id}/catalogs/shared/themes")
        assertThat(back("/catalogs/shared/releases/1.0.0")).isEqualTo("/tenants/${tenant.id}/catalogs/shared/releases")
    }

    @Test
    fun `a newer published version that fits what is used is said so, and no version range is asked for`() {
        val tenant = seed("Workspace Newer Fits")
        withMediator {
            // Shared 1.1.0 only adds something: everything Letters uses still fits.
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), CatalogId(shared, TenantId(tenant.id))), "Seasonal").execute()
            MarkCatalogReady(tenant.id, shared).execute()
            ReleaseCatalogDraft(tenant.id, shared, "1.1.0").execute()
        }

        val page = get(tenant, "/catalogs/letters/dependencies")
        assertThat(page.substringAfter("data-testid=\"catalog-borrowed-newer-fits\"").substringBefore("</div>"))
            .contains("v1.1.0 is published and fits what is used here")
        assertThat(page).doesNotContain("data-testid=\"catalog-borrowed-newer-breaks\"")

        val dialog = htmx(tenant, "/catalogs/letters/dependencies/edit")
        assertThat(dialog).`as`("no range to choose").doesNotContain("dependency-version-").contains("type=\"hidden\" name=\"version\"")
    }

    @Test
    fun `Releases' former address leads there for good, its query kept, and an HTMX request is sent there too`() {
        val tenant = seed("Workspace Former Address")
        val former = "/tenants/${tenant.id}/catalogs/shared/versions?from=bookmark"
        val releases = "/tenants/${tenant.id}/catalogs/shared/releases?from=bookmark"

        val opened = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity(former, String::class.java)
        assertThat(opened.statusCode).isEqualTo(HttpStatus.PERMANENT_REDIRECT)
        assertThat(opened.headers.location.toString()).isEqualTo(releases)

        val swapped = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).exchange(
            former,
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
            String::class.java,
        )
        assertThat(swapped.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(swapped.headers.getFirst("HX-Location")).isEqualTo(releases)

        assertThat(get(tenant, "/catalogs/shared/releases")).`as`("the page at its new address")
            .contains("<span id=\"page-title-text\">Releases</span>").contains("data-testid=\"catalog-versions\"")
    }

    private fun get(tenant: Tenant, path: String): String = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)
        .getForEntity("/tenants/${tenant.id}$path", String::class.java).body!!

    private fun htmx(tenant: Tenant, path: String): String = restTemplate.exchange(
        "/tenants/${tenant.id}$path",
        HttpMethod.GET,
        HttpEntity<Void>(HttpHeaders().apply { set("HX-Request", "true") }),
        String::class.java,
    ).body!!
}

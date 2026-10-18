// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogMetadata
import app.epistola.suite.catalog.dependencies.DeclareUsedCatalogs
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
                    ReleaseCatalogVersion(tenant.id, shared, "1.0.0").execute()
                    val invoice = TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant.id)))
                    CreateDocumentTemplate(invoice, "Invoice").execute().withRequiredDataExample()
                    UpdateDocumentTemplate(invoice, themeId = ThemeKey.of("brand"), themeCatalogKey = shared).execute()
                    DeclareUsedCatalogs(tenant.id, letters).execute()
                }
            }
        }
        return tenant
    }

    @Test
    fun `a list inside a catalog has no catalog to choose, and shows what the catalogs it depends on hold`() {
        val tenant = seed("Workspace Lists")

        val stencils = get(tenant, "/stencils?catalog=letters")
        assertThat(stencils).doesNotContain("id=\"catalog-filter\"")
        val others = stencils.substringAfter("data-testid=\"from-other-catalogs\"")
        assertThat(others).contains(">Footer<").contains("In Shared").contains("href=\"/tenants/${tenant.id}/stencils/shared/footer\"")

        assertThat(get(tenant, "/themes?catalog=letters").substringAfter("data-testid=\"from-other-catalogs\"")).contains(">Brand<")
        assertThat(get(tenant, "/stencils")).`as`("outside a catalog the filter stays").contains("id=\"catalog-filter\"")
            .doesNotContain("data-testid=\"from-other-catalogs\"")
        assertThat(get(tenant, "/stencils?catalog=shared")).`as`("Shared depends on nothing").doesNotContain("data-testid=\"from-other-catalogs\"")
    }

    @Test
    fun `a create dialog opened inside a catalog creates there, with no catalog to choose`() {
        val tenant = seed("Workspace Create")

        listOf("templates", "themes", "stencils", "images", "fonts", "code-lists", "attributes").forEach { area ->
            val dialog = htmx(tenant, "/$area/new?catalog=letters")
            assertThat(dialog).`as`(area).contains("type=\"hidden\" name=\"catalog\" value=\"letters\"").doesNotContain("<select id=\"catalog\"")
        }
        assertThat(htmx(tenant, "/stencils/new")).`as`("outside a catalog it asks").contains("<select id=\"catalog\"")
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
                    add("version", "*")
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

        val about = get(tenant, "/catalogs/letters/browse").substringAfter("data-testid=\"catalog-about\"")
        assertThat(about).contains("Letters to customers").contains("/letters</code>")
            .contains("href=\"/tenants/${tenant.id}/catalogs/letters/settings\"")
    }

    @Test
    fun `every version says what it started from`() {
        val tenant = seed("Workspace Versions")

        val versions = get(tenant, "/catalogs/shared/versions")
        val published = versions.substringAfter("data-version=\"1.0.0\"").substringBefore("</tr>")
        assertThat(published.substringAfter("data-testid=\"catalog-version-base\"")).contains("Started from nothing")
        val next = versions.substringAfter("data-status=\"draft\"").substringBefore("</tr>")
        assertThat(next.substringAfter("data-testid=\"catalog-version-base\"").substringBefore("</td>")).contains(">v1.0.0<")
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

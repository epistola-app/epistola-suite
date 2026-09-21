// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.apikeys.commands.CreateApiKey
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.relocation.MoveCatalogResources
import app.epistola.suite.catalog.relocation.PreviewCatalogResourceMove
import app.epistola.suite.catalog.relocation.ResourceRelocation
import app.epistola.suite.catalog.relocation.movedTo
import app.epistola.suite.catalog.relocation.renamedTo
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.queries.GetDocumentTemplate
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Where an old address still works, and where the alpha deliberately stops.
 *
 * A UI `GET` of a moved template or stencil redirects to its canonical page, and everything beneath
 * it follows. Other types' pages, REST and MCP for themes, fonts, images and code lists do not
 * resolve an old address yet; those limits are documented in `docs/catalog-resource-relocation.md`
 * and pinned here, so lifting one is a deliberate change.
 */
class RelocationAddressSurfacesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")
    private val archive = CatalogKey.of("archive")

    private fun tenant(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator { listOf(letters, shared, archive).forEach { CreateCatalog(tenant, it, it.value).execute() } }
        return tenant
    }

    private fun move(tenant: TenantKey, relocation: ResourceRelocation) = withMediator {
        val plan = PreviewCatalogResourceMove(tenant, listOf(relocation)).query()
        MoveCatalogResources(tenant, listOf(relocation), plan.planFingerprint).execute()
    }

    /** A GET that does not follow redirects, so the redirect itself can be asserted. */
    private fun get(path: String): HttpResponse<String> = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        .send(HttpRequest.newBuilder(URI.create(restTemplate.rootUri + path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun assertRedirects(from: String, to: String) {
        val response = get(from)
        assertThat(response.statusCode()).describedAs(from).isEqualTo(303)
        assertThat(response.headers().firstValue("Location").orElse("")).describedAs(from).endsWith(to)
    }

    @Test
    fun `every page beneath a moved template redirects to the same page at its new address`() {
        val tenant = tenant("Template sub-page redirects")
        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Invoice").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.TEMPLATE, letters.value, "invoice").movedTo(shared))

        val old = "/tenants/$tenant/templates/letters/invoice"
        val new = "/tenants/$tenant/templates/shared/invoice"
        for (page in listOf("", "/settings", "/deployments", "/data-contract", "/variants/default/editor", "/variants/default/versions")) {
            assertRedirects("$old$page", "$new$page")
        }
        assertRedirects("$old/settings?tab=general", "$new/settings?tab=general")
    }

    @Test
    fun `a renamed template, and one moved twice, redirect straight to where it lives now`() {
        val tenant = tenant("Template rename and chain")
        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Invoice").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.TEMPLATE, letters.value, "invoice").renamedTo("bill"))
        move(tenant, ResourceAddress(CatalogResourceType.TEMPLATE, letters.value, "bill").movedTo(archive))

        assertRedirects("/tenants/$tenant/templates/letters/invoice", "/tenants/$tenant/templates/archive/bill")
        assertRedirects("/tenants/$tenant/templates/letters/bill", "/tenants/$tenant/templates/archive/bill")
    }

    @Test
    fun `a moved stencil's pages redirect to its new address`() {
        val tenant = tenant("Stencil redirects")
        withMediator { CreateStencil(StencilId(StencilKey.of("header"), CatalogId(letters, TenantId(tenant))), "Header").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.STENCIL, letters.value, "header").movedTo(shared))

        for (page in listOf("", "/usage", "/versions")) {
            assertRedirects("/tenants/$tenant/stencils/letters/header$page", "/tenants/$tenant/stencils/shared/header$page")
        }
    }

    /** Documented alpha limit: only template and stencil pages redirect. */
    @Test
    fun `a moved theme's old page is not found, as documented`() {
        val tenant = tenant("Theme page limit")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant))), "Brand").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.THEME, letters.value, "brand").movedTo(shared))

        assertThat(get("/tenants/$tenant/themes/letters/brand").statusCode()).isEqualTo(404)
    }

    /** Documented alpha limit: REST resolves old template, stencil and attribute addresses only. */
    @Test
    fun `a moved theme's old REST address is not found, as documented`() {
        val tenant = tenant("Theme REST limit")
        val key = withMediator { CreateApiKey(tenantId = tenant, name = "relocation-it").execute() }.plaintextKey
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant))), "Brand").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.THEME, letters.value, "brand").movedTo(shared))

        val response = restTemplate.exchange("/api/tenants/$tenant/catalogs/letters/themes/brand", HttpMethod.GET, HttpEntity<Void>(api(key)), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /** Documented: a REST write through an old template address applies to the moved template. */
    @Test
    fun `a REST write through a moved template's old address updates the template where it lives now`() {
        val tenant = tenant("Template REST write through alias")
        val key = withMediator { CreateApiKey(tenantId = tenant, name = "relocation-it").execute() }.plaintextKey
        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Invoice").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.TEMPLATE, letters.value, "invoice").movedTo(shared))

        val response = restTemplate.exchange(
            "/api/tenants/$tenant/catalogs/letters/templates/invoice",
            HttpMethod.PATCH,
            HttpEntity("""{"name": "Renamed through the old address"}""", api(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val moved = withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(shared, TenantId(tenant)))).query()!! }
        assertThat(moved.name).isEqualTo("Renamed through the old address")
    }

    private fun api(key: String) = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set("X-API-Key", key)
    }
}

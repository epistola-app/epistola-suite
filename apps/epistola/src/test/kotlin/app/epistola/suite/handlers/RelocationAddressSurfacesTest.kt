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
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
 * A move is crude: no surface follows an old address. UI pages, REST and MCP all answer for the
 * address a resource occupies now, and an old one is simply not found. That is documented in
 * `docs/catalog-resource-relocation.md` and pinned here, so restoring any redirect is a deliberate
 * change.
 */
class RelocationAddressSurfacesTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")
    private val shared = CatalogKey.of("shared")

    private fun tenant(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator { listOf(letters, shared).forEach { CreateCatalog(tenant, it, it.value).execute() } }
        return tenant
    }

    private fun move(tenant: TenantKey, relocation: ResourceRelocation) = withMediator {
        val plan = PreviewCatalogResourceMove(tenant, listOf(relocation)).query()
        MoveCatalogResources(tenant, listOf(relocation), plan.planFingerprint).execute()
    }

    /** A GET that does not follow redirects, so a redirect would show as one. */
    private fun get(path: String): HttpResponse<String> = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
        .send(HttpRequest.newBuilder(URI.create(restTemplate.rootUri + path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `a moved template's old pages are not found, and its new ones are`() {
        val tenant = tenant("Template pages after move")
        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Invoice").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.TEMPLATE, letters.value, "invoice").movedTo(shared))

        for (page in listOf("", "/settings")) {
            assertThat(get("/tenants/$tenant/templates/letters/invoice$page").statusCode()).describedAs("old $page").isEqualTo(404)
            assertThat(get("/tenants/$tenant/templates/shared/invoice$page").statusCode()).describedAs("new $page").isEqualTo(200)
        }
    }

    @Test
    fun `a moved theme's old page is not found`() {
        val tenant = tenant("Theme page after move")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant))), "Brand").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.THEME, letters.value, "brand").movedTo(shared))

        assertThat(get("/tenants/$tenant/themes/letters/brand").statusCode()).isEqualTo(404)
    }

    @Test
    fun `a moved theme's old REST address is not found`() {
        val tenant = tenant("Theme REST after move")
        val key = withMediator { CreateApiKey(tenantId = tenant, name = "relocation-it").execute() }.plaintextKey
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(letters, TenantId(tenant))), "Brand").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.THEME, letters.value, "brand").movedTo(shared))

        val response = restTemplate.exchange("/api/tenants/$tenant/catalogs/letters/themes/brand", HttpMethod.GET, HttpEntity<Void>(api(key)), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a REST write through a moved template's old address is not found, and changes nothing`() {
        val tenant = tenant("Template REST write after move")
        val key = withMediator { CreateApiKey(tenantId = tenant, name = "relocation-it").execute() }.plaintextKey
        withMediator { CreateDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(letters, TenantId(tenant))), "Invoice").execute() }
        move(tenant, ResourceAddress(CatalogResourceType.TEMPLATE, letters.value, "invoice").movedTo(shared))

        val response = restTemplate.exchange(
            "/api/tenants/$tenant/catalogs/letters/templates/invoice",
            HttpMethod.PATCH,
            HttpEntity("""{"name": "Renamed through the old address"}""", api(key)),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val moved = withMediator { GetDocumentTemplate(TemplateId(TemplateKey.of("invoice"), CatalogId(shared, TenantId(tenant)))).query()!! }
        assertThat(moved.name).isEqualTo("Invoice")
    }

    private fun api(key: String) = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("application/vnd.epistola.v1+json")
        accept = listOf(MediaType.parseMediaType("application/vnd.epistola.v1+json"))
        set("X-API-Key", key)
    }
}

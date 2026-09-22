// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.testing.TEST_TENANT_ROLES_HEADER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import tools.jackson.databind.ObjectMapper

/**
 * The organise page and single-move dialog as the server answers them: what a reader is offered,
 * and how a bad request is answered. The component's own behaviour is covered by its unit tests
 * and the browser test.
 */
class CatalogOrganiseSurfaceTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private val letters = CatalogKey.of("letters")

    private fun tenant(name: String, stencils: Int = 1): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            SaveFeatureToggle(tenant, KnownFeatures.RESOURCE_RELOCATION, enabled = true).execute()
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateCatalog(tenant, CatalogKey.of("shared"), "Shared").execute()
            repeat(stencils) { CreateStencil(StencilId(StencilKey.of("header-$it"), CatalogId(letters, TenantId(tenant))), "Header $it").execute() }
        }
        return tenant
    }

    @Test
    fun `a resource type the server does not know is a bad request, not a server error`() {
        val tenant = tenant("Organise unknown type")

        val response = restTemplate.postForEntity(
            "/tenants/$tenant/catalogs/organise/preview",
            HttpEntity("""{"relocations":[{"type":"widget","catalog":"letters","key":"header-0","targetCatalog":"shared"}]}""", json()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /**
     * A boosted link or a history restore sends HX-Request too, but swaps the whole body: a bare
     * dialog there replaces the page with a fragment that has nothing to open it.
     */
    @Test
    fun `a boosted or history-restore visit to the single move gets the whole page`() {
        val tenant = tenant("Organise boosted")
        val url = "/tenants/$tenant/catalogs/organise/move?resource=stencil:letters/header-0"

        for (header in listOf("HX-Boosted", "HX-History-Restore-Request")) {
            val response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                HttpEntity<Void>(
                    HttpHeaders().apply {
                        set("HX-Request", "true")
                        set(header, "true")
                    },
                ),
                String::class.java,
            )
            assertThat(response.body).describedAs(header).contains("<html").doesNotContain("move-resource-dialog")
        }
    }

    /**
     * The browser shows the first page of resources and searches for the rest; a deep link or the
     * single-move dialog names one resource, which must be offered wherever it falls.
     */
    @Test
    fun `a named resource is offered even beyond the first page, and a cut-off page says so`() {
        val tenant = tenant("Organise beyond first page", stencils = 55)

        val body = objectMapper.readTree(
            restTemplate.getForEntity("/tenants/$tenant/catalogs/organise/resources?resource=stencil:letters/header-9", String::class.java).body,
        )

        val offered = buildList { body.path("resources").forEach { add(it.path("id").stringValue()) } }
        assertThat(offered).contains("stencil:letters/header-9")
        assertThat(body.path("truncated").booleanValue()).isTrue()
    }

    /**
     * A catalog viewer can browse and preview a move, but applying needs catalog management. The
     * page must know that up front rather than offer Apply and fail on it.
     */
    @Test
    fun `the page tells the component whether its reader can apply a move`() {
        val tenant = tenant("Organise apply permission")

        val manager = restTemplate.getForEntity("/tenants/$tenant/catalogs/organise", String::class.java)
        val viewer = restTemplate.exchange(
            "/tenants/$tenant/catalogs/organise",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set(TEST_TENANT_ROLES_HEADER, "CONTENT_VIEWER") }),
            String::class.java,
        )

        assertThat(manager.body).contains("data-can-apply=\"true\"")
        assertThat(viewer.body).contains("data-can-apply=\"false\"")
    }

    /** The catalog list's dialogs render the list as a full page too; the Organise action belongs there as well. */
    @Test
    fun `the catalog list offers Organise on every full-page render`() {
        val tenant = tenant("Organise on catalog pages")

        for (path in listOf("", "/new")) {
            val page = restTemplate.getForEntity("/tenants/$tenant/catalogs$path", String::class.java)
            assertThat(page.body).describedAs("/catalogs%s", path).contains("catalog-organise-open")
        }
    }

    private fun json() = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
}

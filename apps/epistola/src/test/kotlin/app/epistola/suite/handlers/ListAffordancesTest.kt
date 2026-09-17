// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * A control appears only when it can be used (`.agents/rules/ui-affordances.md`).
 *
 * Two shapes of the same rule, on the screens where they were reported: a search box offered for
 * a list with nothing in it, and image pickers offered for a catalog that has no images.
 */
class ListAffordancesTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private fun get(path: String): String {
        val response = restTemplate.getForEntity(path, String::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        return requireNotNull(response.body)
    }

    private fun searchBoxCount(body: String) = Regex("""data-testid="search-input"""").findAll(body).count()

    @Test
    fun `an empty list carries no search box`() {
        val tenant = createTenant("Empty List Search")

        val body = get("/tenants/${tenant.id.value}/environments")

        assertThat(searchBoxCount(body)).isZero()
        // The action that would fill the list is still offered — it is the search that has no subject.
        assertThat(body).contains("""data-testid="environment-create-open"""")
    }

    @Test
    fun `a list with something in it carries a search box`() {
        val tenant = createTenant("Populated List Search")
        withMediator { createEnvironment(tenant.id.value, "production", "Production") }

        val body = get("/tenants/${tenant.id.value}/environments")

        assertThat(searchBoxCount(body)).isOne()
    }

    /**
     * The regression that matters, and the reason the gate is safe. Searching swaps only the rows,
     * never the page header — so a term that matches nothing cannot take the box away with it. Were
     * the header re-rendered on search, there would be no way to clear the term.
     */
    @Test
    fun `a search response never re-renders the header, so the box cannot vanish under an active term`() {
        val tenant = createTenant("Search No Matches")
        withMediator { createEnvironment(tenant.id.value, "production", "Production") }

        val response = restTemplate.exchange(
            "/tenants/${tenant.id.value}/environments/search?q=zzzznomatch",
            HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { add("HX-Request", "true") }),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = requireNotNull(response.body)
        // Rows only: no page header in the response at all, so the box on screen is untouched.
        assertThat(searchBoxCount(body)).isZero()
        assertThat(body).doesNotContain("class=\"page-header\"")
    }

    /**
     * The tenants page calls `search-box` directly rather than through `page-header`, so it has its
     * own copy of the gate. Guards the precedence trap too: `th:if` on the same element as
     * `th:replace` never runs, so the guard has to sit on an outer element.
     */
    @Test
    fun `the tenant list carries exactly one search box when tenants exist`() {
        createTenant("Tenant List Search")

        // The tenant list is the application root, not /tenants (TenantRoutes).
        val body = get("/")

        assertThat(searchBoxCount(body)).isOne()
    }

    @Test
    fun `the presentation dialog explains a catalog with no images instead of offering empty pickers`() {
        val tenant = createTenant("Presentation Empty")
        val catalogKey = CatalogKey.of("presentation-empty")
        withMediator { CreateCatalog(tenant.id, catalogKey, "Presentation empty").execute() }

        val body = get("/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/metadata?section=presentation")

        assertThat(body).contains("This catalog has no images yet")
        assertThat(body).doesNotContain("""name="imageAssetSlugs"""")
        assertThat(body).doesNotContain("""name="iconAssetSlug"""")
    }

    @Test
    fun `the presentation dialog offers its pickers once the catalog has an image`() {
        val tenant = createTenant("Presentation WithImage")
        val catalogKey = CatalogKey.of("presentation-with-image")
        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Presentation with image").execute()
            UploadAsset(
                tenantId = tenant.id,
                name = "Pixel",
                mediaType = AssetMediaType.SVG,
                content = """<svg xmlns="http://www.w3.org/2000/svg"/>""".toByteArray(),
                width = null,
                height = null,
                catalogKey = catalogKey,
            ).execute()
        }

        val body = get("/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/metadata?section=presentation")

        assertThat(body).doesNotContain("This catalog has no images yet")
        assertThat(body).contains("""name="imageAssetSlugs"""")
        assertThat(body).contains("""name="iconAssetSlug"""")
    }

    /** The catalog page withholds the button that opens that dialog, and says where images come from. */
    @Test
    fun `the catalog page withholds the presentation control until there is an image`() {
        val tenant = createTenant("Presentation Card")
        val catalogKey = CatalogKey.of("presentation-card")
        withMediator { CreateCatalog(tenant.id, catalogKey, "Presentation card").execute() }

        val body = get("/tenants/${tenant.id.value}/catalogs/${catalogKey.value}/browse")

        assertThat(body).contains("This catalog has no images yet")
        assertThat(body).doesNotContain("section='presentation'")
    }

    private fun createEnvironment(tenantKeyValue: String, key: String, name: String) = CreateEnvironment(
        id = EnvironmentId(EnvironmentKey.of(key), TenantId(app.epistola.suite.common.ids.TenantKey.of(tenantKeyValue))),
        name = name,
    ).execute()
}

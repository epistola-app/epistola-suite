// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ForgetReleaseContent
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * The catalog page's Deployments card: one row per environment with the release of this catalog it
 * serves, a deploy action where there is something to deploy, and a reason where there is not.
 */
class CatalogDeploymentsPageTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `each environment is listed with what it serves, and a release can be deployed to it`() {
        val tenant = tenantWithCatalog("dep-page")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = CatalogKey.of("dep-page"), version = "1.0.0").execute()
        }

        val before = browse(tenant, "dep-page")
        assertThat(before).contains("<h2>Deployments</h2>").contains("Production").contains(">nothing<")
        assertThat(before).contains("catalogs/dep-page/deployments").contains("value=\"1.0.0\"")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/dep-page/deployments",
            form("environmentId" to "production", "version" to "1.0.0"),
            String::class.java,
        )
        assertThat(response.statusCode.is2xxSuccessful).`as`("the redirect lands back on the catalog").isTrue()

        val served = withMediator { ListDeployments(tenant.id, CatalogKey.of("dep-page")).query() }
        assertThat(served.map { it.environmentKey.value to it.version }).containsExactly("production" to "1.0.0")
        assertThat(browse(tenant, "dep-page")).contains("v1.0.0").contains("Undeploy")
    }

    @Test
    fun `with no environments the card says so and links to where they are added`() {
        val tenant = tenantWithCatalog("dep-noenv")

        val body = browse(tenant, "dep-noenv")

        assertThat(body).contains("There are no environments to deploy to yet").contains("/tenants/${tenant.id.value}/environments")
        assertThat(body).doesNotContain("catalogs/dep-noenv/deployments\"")
    }

    @Test
    fun `with no release that kept its content there is nothing to deploy, and the card says why`() {
        val tenant = tenantWithCatalog("dep-norelease")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = CatalogKey.of("dep-norelease"), version = "1.0.0").execute()
            ForgetReleaseContent(tenant.id, CatalogKey.of("dep-norelease"), "1.0.0").execute()
        }

        val body = browse(tenant, "dep-norelease")

        assertThat(body).contains("Nothing can be deployed yet")
        assertThat(body).`as`("no deploy form with an empty select").doesNotContain("name=\"version\"")
    }

    @Test
    fun `forgetting a deployed release is refused on the page, naming the environment`() {
        val tenant = tenantWithCatalog("dep-guard")
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
            CreateEnvironment(production, "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = CatalogKey.of("dep-guard"), version = "1.0.0").execute()
            DeployRelease(production, CatalogKey.of("dep-guard"), "1.0.0").execute()
        }

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/dep-guard/releases/1.0.0/forget",
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(response.body).contains("deployed to &#39;production&#39;")
    }

    private fun catalog(tenant: Tenant) = CatalogId(CatalogKey.of(tenant.name.substringAfterLast(' ')), TenantId(tenant.id))

    private fun tenantWithCatalog(slug: String): Tenant {
        lateinit var created: Tenant
        fixture {
            given {
                created = tenant("Deployments $slug")
                withMediator { CreateCatalog(tenantKey = created.id, id = CatalogKey.of(slug), name = slug).execute() }
            }
        }
        return created
    }

    private fun form(vararg fields: Pair<String, String>): HttpEntity<LinkedMultiValueMap<String, String>> {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }
        val body = LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } }
        return HttpEntity(body, headers)
    }

    private fun browse(tenant: Tenant, catalogKey: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id.value}/catalogs/$catalogKey/browse", String::class.java)
        .body!!
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.ForgetReleaseContent
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogVersion
import app.epistola.suite.catalog.commands.UpdateCatalogDependencies
import app.epistola.suite.catalog.queries.GetCatalog
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
 * The catalog page's Deployments card: one row per environment with every release of this catalog it
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
            MarkCatalogReady(tenant.id, CatalogKey.of("dep-page")).execute()
            ReleaseCatalogVersion(tenant.id, CatalogKey.of("dep-page"), version = "1.0.0").execute()
        }

        val before = live(tenant, "dep-page")
        assertThat(before).contains("<h2>Live</h2>").contains("Production").contains(">not live<")
        assertThat(before).contains("catalogs/dep-page/deployments").contains("value=\"1.0.0\"")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/dep-page/deployments",
            form("environmentId" to "production", "version" to "1.0.0"),
            String::class.java,
        )
        assertThat(response.statusCode.is2xxSuccessful).`as`("the redirect lands back on the Live page").isTrue()

        val served = withMediator { ListDeployments(tenant.id, CatalogKey.of("dep-page")).query() }
        assertThat(served.map { it.environmentKey.value to it.version }).containsExactly("production" to "1.0.0")
        assertThat(live(tenant, "dep-page")).contains("v1.0.0").contains("Take offline")
    }

    @Test
    fun `an environment serving two releases lists both, each with its own undeploy, and undeploying keeps the other`() {
        val tenant = tenantWithCatalog("dep-two")
        val key = CatalogKey.of("dep-two")
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
            CreateEnvironment(production, "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogVersion(tenant.id, key, version = "1.0.0").execute()
            CreateTheme(ThemeId(ThemeKey.of("second"), catalog(tenant)), "Second").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogVersion(tenant.id, key, version = "1.0.1").execute()
            DeployRelease(production, key, "1.0.0").execute()
            DeployRelease(production, key, "1.0.1").execute()
        }

        val body = live(tenant, "dep-two")
        assertThat(body).contains("data-version=\"1.0.1\"").contains("data-version=\"1.0.0\"")
        assertThat(body).contains("name=\"version\" value=\"1.0.1\"").contains("name=\"version\" value=\"1.0.0\"")
        assertThat(body).contains("v1.0.1 (live)").contains("v1.0.0 (live)")

        restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/dep-two/deployments/production/undeploy",
            form("version" to "1.0.1"),
            String::class.java,
        )

        assertThat(withMediator { ListDeployments(tenant.id, key).query() }.map { it.version }).containsExactly("1.0.0")
    }

    @Test
    fun `making a version live on the catalog page replaces the one that was live there`() {
        val tenant = tenantWithCatalog("dep-replace")
        val key = CatalogKey.of("dep-replace")
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
            CreateEnvironment(production, "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogVersion(tenant.id, key, version = "1.0.0").execute()
            CreateTheme(ThemeId(ThemeKey.of("second"), catalog(tenant)), "Second").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogVersion(tenant.id, key, version = "1.1.0").execute()
            DeployRelease(production, key, "1.0.0").execute()
        }

        restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/dep-replace/deployments",
            form("environmentId" to "production", "version" to "1.1.0"),
            String::class.java,
        )

        assertThat(withMediator { ListDeployments(tenant.id, key).query() }.map { it.version }).containsExactly("1.1.0")
    }

    @Test
    fun `a deploy refused for a missing dependency shows it on the card, offering to deploy it along`() {
        val tenant = tenantWithCatalog("dep-deps")
        val key = CatalogKey.of("dep-deps")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            val shared = CatalogKey.of("shared")
            CreateCatalog(tenantKey = tenant.id, id = shared, name = "shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant.id))), "Brand").execute()
            MarkCatalogReady(tenant.id, shared).execute()
            ReleaseCatalogVersion(tenant.id, shared, version = "1.0.0").execute()
            val target = GetCatalog(tenant.id, shared).query()!!
            UpdateCatalogDependencies(tenant.id, key, listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "*"))).execute()
            CreateTheme(ThemeId(ThemeKey.of("own"), catalog(tenant)), "Own").execute()
            MarkCatalogReady(tenant.id, key).execute()
            ReleaseCatalogVersion(tenant.id, key, version = "1.0.0").execute()
        }

        val refused = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/dep-deps/deployments",
            form("environmentId" to "production", "version" to "1.0.0"),
            String::class.java,
        )

        assertThat(refused.body).contains("data-testid=\"deploy-refusal\"").contains("data-reason=\"NOT_DEPLOYED\"")
            .contains("action=\"/tenants/${tenant.id.value}/catalogs/dep-deps/deployments\"")
            .contains("name=\"environmentId\" value=\"production\"").contains("name=\"includeDependencies\" value=\"true\"")

        restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/dep-deps/deployments",
            form("environmentId" to "production", "version" to "1.0.0", "includeDependencies" to "true"),
            String::class.java,
        )
        assertThat(withMediator { ListDeployments(tenant.id).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .contains("dep-deps@1.0.0", "shared@1.0.0")
    }

    @Test
    fun `with no environments the card says so and links to where they are added`() {
        val tenant = tenantWithCatalog("dep-noenv")

        val body = live(tenant, "dep-noenv")

        assertThat(body).contains("There are no environments yet").contains("/tenants/${tenant.id.value}/environments")
        assertThat(body).doesNotContain("catalogs/dep-noenv/deployments\"")
    }

    @Test
    fun `with no release that kept its content there is nothing to deploy, and the card says why`() {
        val tenant = tenantWithCatalog("dep-norelease")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            MarkCatalogReady(tenant.id, CatalogKey.of("dep-norelease")).execute()
            ReleaseCatalogVersion(tenant.id, CatalogKey.of("dep-norelease"), version = "1.0.0").execute()
            ForgetReleaseContent(tenant.id, CatalogKey.of("dep-norelease"), "1.0.0").execute()
        }

        val body = live(tenant, "dep-norelease")

        assertThat(body).contains("Nothing can be made live yet")
        assertThat(body).`as`("no deploy form with an empty select").doesNotContain("name=\"version\"")
    }

    @Test
    fun `forgetting a deployed release is refused on the page, naming the environment`() {
        val tenant = tenantWithCatalog("dep-guard")
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
            CreateEnvironment(production, "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            MarkCatalogReady(tenant.id, CatalogKey.of("dep-guard")).execute()
            ReleaseCatalogVersion(tenant.id, CatalogKey.of("dep-guard"), version = "1.0.0").execute()
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

    /** The catalog's Live page (docs/workspace-shell.md), where what each environment serves is shown. */
    private fun live(tenant: Tenant, catalogKey: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id.value}/catalogs/$catalogKey/live", String::class.java)
        .body!!
}

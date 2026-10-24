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
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
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
            MarkCatalogReady(testCatalog(tenant.id, CatalogKey.of("dep-page"))).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, CatalogKey.of("dep-page")), version = "1.0.0").execute()
        }

        val before = live(tenant, "dep-page")
        assertThat(before).contains("<h2>Environments</h2>").contains("Production").contains(">not deployed<")
        assertThat(before).contains("catalogs/${tenant.id}/dep-page/deployments").contains("value=\"1.0.0\"")

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/dep-page/deployments",
            form("environmentId" to "production", "version" to "1.0.0"),
            String::class.java,
        )
        assertThat(response.statusCode.is2xxSuccessful).`as`("the redirect lands back on the Deployments page").isTrue()

        val served = withMediator { ListDeployments(tenant.id, testCatalog(tenant.id, CatalogKey.of("dep-page"))).query() }
        assertThat(served.map { it.environmentKey.value to it.version }).containsExactly("production" to "1.0.0")
        assertThat(live(tenant, "dep-page")).contains("v1.0.0").contains("Undeploy")
    }

    @Test
    fun `an environment serving two releases lists both, each with its own undeploy, and undeploying keeps the other`() {
        val tenant = tenantWithCatalog("dep-two")
        val key = CatalogKey.of("dep-two")
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
            CreateEnvironment(production, "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            MarkCatalogReady(testCatalog(tenant.id, key)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, key), version = "1.0.0").execute()
            // A correction, so the next patch.
            UpdateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), name = "Brand, corrected").execute()
            MarkCatalogReady(testCatalog(tenant.id, key)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, key), version = "1.0.1").execute()
            DeployCatalogRelease(production, testCatalog(production.tenantKey, key), "1.0.0").execute()
            DeployCatalogRelease(production, testCatalog(production.tenantKey, key), "1.0.1").execute()
        }

        val body = live(tenant, "dep-two")
        assertThat(body).contains("data-version=\"1.0.1\"").contains("data-version=\"1.0.0\"")
        assertThat(body).contains("name=\"version\" value=\"1.0.1\"").contains("name=\"version\" value=\"1.0.0\"")
        assertThat(body).contains("v1.0.1 (deployed)").contains("v1.0.0 (deployed)")

        restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/dep-two/deployments/production/undeploy",
            form("version" to "1.0.1"),
            String::class.java,
        )

        assertThat(withMediator { ListDeployments(tenant.id, testCatalog(tenant.id, key)).query() }.map { it.version }).containsExactly("1.0.0")
    }

    @Test
    fun `deploying on the catalog page adds the version, and replaces the others only when asked`() {
        val tenant = tenantWithCatalog("dep-replace")
        val key = CatalogKey.of("dep-replace")
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
            CreateEnvironment(production, "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            MarkCatalogReady(testCatalog(tenant.id, key)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, key), version = "1.0.0").execute()
            // A correction, so the next patch.
            UpdateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), name = "Brand, corrected").execute()
            MarkCatalogReady(testCatalog(tenant.id, key)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, key), version = "1.1.0").execute()
            DeployCatalogRelease(production, testCatalog(production.tenantKey, key), "1.0.0").execute()
        }

        restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/dep-replace/deployments",
            form("environmentId" to "production", "version" to "1.1.0"),
            String::class.java,
        )
        assertThat(withMediator { ListDeployments(tenant.id, testCatalog(tenant.id, key)).query() }.map { it.version })
            .`as`("beside the other: requests pinned to 1.0.0 keep working").containsExactly("1.1.0", "1.0.0")

        restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/dep-replace/deployments",
            form("environmentId" to "production", "version" to "1.1.0", "replace" to "true"),
            String::class.java,
        )
        assertThat(withMediator { ListDeployments(tenant.id, testCatalog(tenant.id, key)).query() }.map { it.version }).containsExactly("1.1.0")
    }

    @Test
    fun `deploying a catalog uses what it depends on implicitly, and both pages say it comes along`() {
        val tenant = tenantWithCatalog("dep-deps")
        val key = CatalogKey.of("dep-deps")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            val shared = CatalogKey.of("shared")
            CreateCatalog(tenantKey = tenant.id, id = shared, name = "Shared").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(TenantId(tenant.id), shared)), "Brand").execute()
            MarkCatalogReady(testCatalog(tenant.id, shared)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, shared), version = "1.0.0").execute()
            val target = GetCatalog(testCatalog(tenant.id, shared)).query()!!
            UpdateCatalogDependencies(testCatalog(tenant.id, key), listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "1.0.0"))).execute()
            CreateTheme(ThemeId(ThemeKey.of("own"), catalog(tenant)), "Own").execute()
            MarkCatalogReady(testCatalog(tenant.id, key)).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, key), version = "1.0.0").execute()
        }

        val deployed = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/dep-deps/deployments",
            form("environmentId" to "production", "version" to "1.0.0"),
            String::class.java,
        )

        assertThat(deployed.body).doesNotContain("data-testid=\"deploy-refusal\"")
        assertThat(withMediator { ListDeployments(tenant.id).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .`as`("what it depends on is used, not deployed").containsExactly("dep-deps@1.0.0")
        assertThat(live(tenant, "shared").substringAfter("data-environment=\"production\"").substringBefore("</tr>"))
            .contains("v1.0.0, comes along with dep-deps")
        val environment = restTemplate.getForEntity("/tenants/${tenant.id.value}/environments/production", String::class.java).body!!
        assertThat(environment.substringAfter("data-testid=\"environment-implicit\"")).contains(">Shared<").contains("v1.0.0").contains("dep-deps")
    }

    @Test
    fun `with no environments the card says so and links to where they are added`() {
        val tenant = tenantWithCatalog("dep-noenv")

        val body = live(tenant, "dep-noenv")

        assertThat(body).contains("There are no environments yet").contains("/tenants/${tenant.id.value}/environments")
        assertThat(body).`as`("no deploy form").doesNotContain("action=\"/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/dep-noenv/deployments\"")
    }

    @Test
    fun `with no release there is nothing to deploy, and the card says why`() {
        val tenant = tenantWithCatalog("dep-norelease")
        withMediator {
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
        }

        val body = live(tenant, "dep-norelease")

        assertThat(body).contains("Nothing is published yet")
        assertThat(body).`as`("no published version to offer").doesNotContain("label=\"Published releases\"")
        assertThat(body).`as`("an environment that is not production can follow the next version").contains("label=\"Follow a draft\"")
    }

    @Test
    fun `deleting a deployed release is refused on the page, naming the environment`() {
        val tenant = tenantWithCatalog("dep-guard")
        withMediator {
            val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
            CreateEnvironment(production, "Production").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog(tenant)), "Brand").execute()
            MarkCatalogReady(testCatalog(tenant.id, CatalogKey.of("dep-guard"))).execute()
            ReleaseCatalogDraft(testCatalog(tenant.id, CatalogKey.of("dep-guard")), version = "1.0.0").execute()
            DeployCatalogRelease(production, testCatalog(production.tenantKey, CatalogKey.of("dep-guard")), "1.0.0").execute()
        }

        val response = restTemplate.postForEntity(
            "/tenants/${tenant.id.value}/catalogs/${tenant.id.value}/dep-guard/releases/1.0.0/delete",
            HttpEntity<Void>(HttpHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(response.body).contains("deployed to &#39;production&#39;")
    }

    private fun catalog(tenant: Tenant) = testCatalog(TenantId(tenant.id), CatalogKey.of(tenant.name.substringAfterLast(' ')))

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

    /** The catalog's Deployments page (docs/workspace-shell.md), where what each environment serves is shown. */
    private fun live(tenant: Tenant, catalogKey: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id.value}/catalogs/$catalogKey/deployments", String::class.java)
        .body!!
}

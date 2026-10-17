// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

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
import app.epistola.suite.environments.queries.ListDeployableReleases
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * An environment's page: every release it serves, a way to deploy another, what it served before
 * with an earlier release offered again where deploying it would change something, and a refused
 * deploy's missing dependencies with the offer to deploy them along when that would succeed.
 */
class EnvironmentDetailPageTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `the page lists every release served, each with its own undeploy, and offers only unserved releases again`() {
        val (tenant, production) = tenantWithReleases("env-page")
        withMediator {
            DeployRelease(production, LETTERS, "1.0.0").execute()
            DeployRelease(production, LETTERS, "1.0.1").execute()
        }

        val body = page(tenant, "production")

        assertThat(body).contains("<h2>Serves</h2>").contains("<h2>History</h2>")
        assertThat(body).contains("data-catalog=\"letters\" data-version=\"1.0.1\"").contains("data-catalog=\"letters\" data-version=\"1.0.0\"")
        assertThat(body).contains("name=\"version\" value=\"1.0.1\"").contains("name=\"version\" value=\"1.0.0\"")
        assertThat(body).contains("Deployed letters v1.0.1").doesNotContain("replacing v")
        // The deploy select lists the catalog's releases, marking each one served.
        assertThat(body).contains("letters v1.0.1 (serving)").contains("letters v1.0.0 (serving)")
        assertThat(body).`as`("both are served, so neither is offered again")
            .doesNotContain("Deploy v1.0.0 again").doesNotContain("Deploy v1.0.1 again")
    }

    @Test
    fun `deploying from the page adds the release and logs it`() {
        val (tenant, production) = tenantWithReleases("env-deploy")
        withMediator { DeployRelease(production, LETTERS, "1.0.1").execute() }

        val response = post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@1.0.0")

        assertThat(response.statusCode.is2xxSuccessful).`as`("the redirect lands back on the environment").isTrue()
        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { it.version }).containsExactly("1.0.1", "1.0.0")
        assertThat(withMediator { ListDeploymentHistory(production).query() }.first().version).isEqualTo("1.0.0")
    }

    @Test
    fun `undeploying one release from the page keeps the others and records it`() {
        val (tenant, production) = tenantWithReleases("env-undeploy")
        withMediator {
            DeployRelease(production, LETTERS, "1.0.0").execute()
            DeployRelease(production, LETTERS, "1.0.1").execute()
        }

        post("/tenants/${tenant.id.value}/environments/production/deployments/letters/undeploy", "version" to "1.0.1")

        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { it.version }).containsExactly("1.0.0")
        assertThat(page(tenant, "production")).contains("Undeployed letters v1.0.1").contains("Deploy v1.0.1 again")
    }

    @Test
    fun `a deploy refused for a missing dependency lists it, and deploying with its dependencies deploys both`() {
        val (tenant, production) = tenantWithReleases("env-deps")
        val version = releaseDependingOnShared(tenant)

        val refused = post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@$version")

        assertThat(refused.body).contains("data-testid=\"deploy-refusal\"").contains("data-reason=\"NOT_DEPLOYED\"")
            .contains("/shared@1.0.0").contains("this environment does not serve it")
        assertThat(refused.body).contains("data-testid=\"deploy-with-dependencies\"").contains("name=\"includeDependencies\" value=\"true\"")
            .contains("name=\"release\" value=\"letters@$version\"")
        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }).isEmpty()

        post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@$version", "includeDependencies" to "true")

        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .containsExactly("letters@$version", "shared@1.0.0")
    }

    @Test
    fun `a missing dependency the tenant does not have withholds deploying along, saying why`() {
        val (tenant, _) = tenantWithReleases("env-deps-missing")
        val version = releaseDependingOnShared(tenant)
        // A pin on a release this tenant lacks arrives only with an imported release; no command cuts one.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE catalog_release_dependencies SET release_version = '9.9.9'
                WHERE tenant_key = :t AND catalog_id = (SELECT catalog_id FROM catalogs WHERE tenant_key = :t AND catalog_key = 'letters')
                """,
            ).bind("t", tenant.id).execute()
        }

        val refused = post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@$version")

        assertThat(refused.body).contains("data-reason=\"NOT_INSTALLED\"").contains("install it first")
        assertThat(refused.body).doesNotContain("data-testid=\"deploy-with-dependencies\"").contains("data-testid=\"deploy-with-dependencies-withheld\"")
    }

    @Test
    fun `an environment with nothing to deploy says so instead of offering an empty select`() {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant("Environment Page Empty")
                CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            }
        }
        // Every tenant gets the system catalog, whose installed release can be deployed. Forgetting
        // its content leaves the tenant with nothing deployable.
        withMediator {
            ListDeployableReleases(tenant.id).query().forEach { ForgetReleaseContent(tenant.id, it.catalogKey, it.version).execute() }
        }

        val body = page(tenant, "production")

        assertThat(body).contains("Nothing can be deployed yet").contains("Nothing has been deployed to this environment yet")
        assertThat(body).doesNotContain("name=\"release\"")
    }

    @Test
    fun `a release that cannot be deployed is refused on the page`() {
        val (tenant, _) = tenantWithReleases("env-refused")

        val response = post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@9.9.9")

        assertThat(response.statusCode.is2xxSuccessful).isTrue()
        assertThat(response.body).contains("cannot be deployed: no such release")
    }

    @Test
    fun `an environment that does not exist is not found`() {
        val (tenant, _) = tenantWithReleases("env-missing")

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}/environments/nowhere", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /** `shared` released as 1.0.0, and the next release of `letters`, cut while depending on it. */
    private fun releaseDependingOnShared(tenant: Tenant): String = withMediator {
        val shared = CatalogKey.of("shared")
        CreateCatalog(tenantKey = tenant.id, id = shared, name = "shared").execute()
        CreateTheme(ThemeId(ThemeKey.of("brand"), CatalogId(shared, TenantId(tenant.id))), "Brand").execute()
        MarkCatalogReady(tenant.id, shared).execute()
        ReleaseCatalogVersion(tenant.id, shared, version = "1.0.0").execute()
        val target = GetCatalog(tenant.id, shared).query()!!
        UpdateCatalogDependencies(tenant.id, LETTERS, listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "*"))).execute()
        MarkCatalogReady(tenant.id, LETTERS).execute()
        ReleaseCatalogVersion(tenant.id, LETTERS, version = "1.0.2").execute()
        "1.0.2"
    }

    /** A catalog `letters` with releases 1.0.0 and 1.0.1, and an environment `production`. */
    private fun tenantWithReleases(slug: String): Pair<Tenant, EnvironmentId> {
        lateinit var tenant: Tenant
        fixture { given { tenant = tenant("Environment Page $slug") } }
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenantKey = tenant.id, id = LETTERS, name = "letters").execute()
            CreateEnvironment(production, "Production").execute()
            val catalog = CatalogId(LETTERS, TenantId(tenant.id))
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
            MarkCatalogReady(tenant.id, LETTERS).execute()
            ReleaseCatalogVersion(tenant.id, LETTERS, version = "1.0.0").execute()
            CreateTheme(ThemeId(ThemeKey.of("second"), catalog), "Second").execute()
            MarkCatalogReady(tenant.id, LETTERS).execute()
            ReleaseCatalogVersion(tenant.id, LETTERS, version = "1.0.1").execute()
        }
        return tenant to production
    }

    private fun post(path: String, vararg fields: Pair<String, String>) = restTemplate.postForEntity(
        path,
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } },
            HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED },
        ),
        String::class.java,
    )

    private fun page(tenant: Tenant, environment: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id.value}/environments/$environment", String::class.java)
        .body!!

    private companion object {
        val LETTERS: CatalogKey = CatalogKey.of("letters")
    }
}

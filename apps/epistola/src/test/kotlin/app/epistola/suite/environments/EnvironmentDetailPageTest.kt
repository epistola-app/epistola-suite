// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeclaredCatalogDependency
import app.epistola.suite.catalog.commands.DeleteCatalogRelease
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
import app.epistola.suite.environments.commands.UndeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployableReleases
import app.epistola.suite.environments.queries.ListDeploymentHistory
import app.epistola.suite.environments.queries.ListDeployments
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
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
            DeployCatalogRelease(production, LETTERS, "1.0.0").execute()
            DeployCatalogRelease(production, LETTERS, "1.0.1").execute()
        }

        val body = page(tenant, "production")

        assertThat(body).contains("<h2>Deployed here</h2>").contains("<h2>History</h2>")
        assertThat(body).contains("data-catalog=\"letters\" data-version=\"1.0.1\"").contains("data-catalog=\"letters\" data-version=\"1.0.0\"")
        assertThat(body).contains("name=\"version\" value=\"1.0.1\"").contains("name=\"version\" value=\"1.0.0\"")
        assertThat(body).contains("Deployed letters v1.0.1").doesNotContain("replacing v")
        // The deploy select lists the catalog's releases, marking each one served.
        assertThat(body).contains("letters v1.0.1 (deployed)").contains("letters v1.0.0 (deployed)")
        assertThat(body).`as`("both are served, so neither is offered again")
            .doesNotContain("Deploy v1.0.0 again").doesNotContain("Deploy v1.0.1 again")
    }

    @Test
    fun `deploying from the page adds the version, replaces the others when asked, and the history says which`() {
        val (tenant, production) = tenantWithReleases("env-deploy")
        withMediator { DeployCatalogRelease(production, LETTERS, "1.0.1").execute() }

        val added = post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@1.0.0")
        assertThat(added.statusCode.is2xxSuccessful).`as`("the redirect lands back on the environment").isTrue()
        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { it.version })
            .`as`("beside the other").containsExactly("1.0.1", "1.0.0")
        assertThat(page(tenant, "production")).contains("Not used since it was deployed")

        withMediator { UndeployCatalogRelease(production, LETTERS, "1.0.0").execute() }
        post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@1.0.0", "replace" to "true")
        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { it.version }).containsExactly("1.0.0")
        assertThat(withMediator { ListDeploymentHistory(production).query() }.first().version).isEqualTo("1.0.0")
        assertThat(page(tenant, "production")).contains("Deployed letters v1.0.0").contains("replacing v1.0.1")
    }

    @Test
    fun `undeploying one release from the page keeps the others and records it`() {
        val (tenant, production) = tenantWithReleases("env-undeploy")
        withMediator {
            DeployCatalogRelease(production, LETTERS, "1.0.0").execute()
            DeployCatalogRelease(production, LETTERS, "1.0.1").execute()
        }

        post("/tenants/${tenant.id.value}/environments/production/deployments/letters/undeploy", "version" to "1.0.1")

        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { it.version }).containsExactly("1.0.0")
        assertThat(page(tenant, "production")).contains("Undeployed letters v1.0.1").contains("Deploy v1.0.1 again")
    }

    @Test
    fun `deploying a version uses what it depends on without deploying it, and the page lists what comes along`() {
        val (tenant, production) = tenantWithReleases("env-deps")
        val version = releaseDependingOnShared(tenant)

        val deployed = post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@$version")

        assertThat(deployed.body.orEmpty()).doesNotContain("data-testid=\"deploy-refusal\"")
        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { "${it.catalogKey.value}@${it.version}" })
            .containsExactly("letters@$version")
        assertThat(page(tenant, "production").substringAfter("data-testid=\"environment-implicit\"")).contains("data-catalog=\"shared\"").contains("v1.0.0")
    }

    @Test
    fun `a dependency the tenant does not have refuses the deploy, saying why`() {
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

        // What it depends on is used implicitly, so only a version nothing could render from refuses.
        assertThat(refused.body).contains("data-reason=\"UNAVAILABLE\"").contains("install it first")
        assertThat(refused.body).doesNotContain("data-testid=\"deploy-with-dependencies\"")
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
        // Every tenant gets the system catalog, whose installed release can be deployed. Deleting it
        // leaves the tenant with nothing deployable.
        withMediator {
            ListDeployableReleases(tenant.id).query().forEach { DeleteCatalogRelease(tenant.id, it.catalogKey, it.version).execute() }
        }

        val body = page(tenant, "production")

        assertThat(body).contains("Nothing can be deployed yet").contains("Nothing has been deployed here yet")
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
        ReleaseCatalogDraft(tenant.id, shared, version = "1.0.0").execute()
        val target = GetCatalog(tenant.id, shared).query()!!
        UpdateCatalogDependencies(tenant.id, LETTERS, listOf(DeclaredCatalogDependency(target.registry, target.namespace, "shared", "1.0.0"))).execute()
        MarkCatalogReady(tenant.id, LETTERS).execute()
        // A dependency added is a breaking change: released as the number that needs.
        ReleaseCatalogDraft(tenant.id, LETTERS).execute().version
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
            ReleaseCatalogDraft(tenant.id, LETTERS, version = "1.0.0").execute()
            // A correction, so the next patch.
            UpdateTheme(ThemeId(ThemeKey.of("brand"), catalog), name = "Brand, corrected").execute()
            MarkCatalogReady(tenant.id, LETTERS).execute()
            ReleaseCatalogDraft(tenant.id, LETTERS, version = "1.0.1").execute()
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

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments

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
import app.epistola.suite.environments.queries.ListDeployableReleases
import app.epistola.suite.environments.queries.ListDeploymentHistory
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * An environment's page: what it serves, a way to deploy another release, and what it served before,
 * with an earlier release offered again where deploying it would change something.
 */
class EnvironmentDetailPageTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the page shows what is served and the history, offering only earlier releases again`() {
        val (tenant, production) = tenantWithReleases("env-page")
        withMediator {
            DeployRelease(production, LETTERS, "1.0.0").execute()
            DeployRelease(production, LETTERS, "1.0.1").execute()
        }

        val body = page(tenant, "production")

        assertThat(body).contains("<h2>Serves</h2>").contains("<h2>History</h2>")
        assertThat(body).contains("Deployed letters v1.0.1").contains("replacing v1.0.0")
        // The deploy select lists the catalog's releases, marking the one served.
        assertThat(body).contains("value=\"letters@1.0.1\"").contains("letters v1.0.1 (serving)")
        assertThat(body).`as`("the earlier release can be deployed again").contains("Deploy v1.0.0 again")
        assertThat(body).`as`("the served one is not offered again").doesNotContain("Deploy v1.0.1 again")
    }

    @Test
    fun `deploying from the page moves the environment and logs the change`() {
        val (tenant, production) = tenantWithReleases("env-deploy")
        withMediator { DeployRelease(production, LETTERS, "1.0.1").execute() }

        val response = post("/tenants/${tenant.id.value}/environments/production/deployments", "release" to "letters@1.0.0")

        assertThat(response.statusCode.is2xxSuccessful).`as`("the redirect lands back on the environment").isTrue()
        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }.map { it.version }).containsExactly("1.0.0")
        assertThat(withMediator { ListDeploymentHistory(production).query() }.first().previousVersion).isEqualTo("1.0.1")
        assertThat(response.body).contains("Deploy v1.0.1 again")
    }

    @Test
    fun `undeploying from the page clears what is served and records it`() {
        val (tenant, production) = tenantWithReleases("env-undeploy")
        withMediator { DeployRelease(production, LETTERS, "1.0.1").execute() }

        post("/tenants/${tenant.id.value}/environments/production/deployments/letters/undeploy")

        assertThat(withMediator { ListDeployments(tenant.id, environmentKey = production.key).query() }).isEmpty()
        assertThat(page(tenant, "production")).contains("Undeployed letters v1.0.1").contains("serves no catalog yet")
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
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = LETTERS, version = "1.0.0").execute()
            CreateTheme(ThemeId(ThemeKey.of("second"), catalog), "Second").execute()
            ReleaseCatalogVersion(tenantKey = tenant.id, catalogKey = LETTERS, version = "1.0.1").execute()
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

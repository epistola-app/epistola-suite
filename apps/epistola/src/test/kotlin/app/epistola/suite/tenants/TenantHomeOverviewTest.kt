// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.DeleteCatalog
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.ListCatalogReleases
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployCatalogRelease
import app.epistola.suite.environments.queries.ListDeployableReleases
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

/**
 * A tenant lands on an overview of its catalogs (docs/catalog-workspace-navigation.md): each with its
 * drafts and what they are behind, its latest release, what waits to be marked ready and where it is
 * served, each version linking into it.
 */
class TenantHomeOverviewTest : BaseIntegrationTest() {
    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the tenant home shows each catalog's next version, parallel work, published version and where it is live`() {
        val tenant = createTenant("Home Overview")
        val letters = CatalogKey.of("letters")
        val catalog = CatalogId(letters, TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
            mediator.releaseNext(catalog)
            CreateCatalogDraft(tenant.id, letters, "Redesign").execute()
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalog), "Seasonal").execute()
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            DeployCatalogRelease(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), letters, "1.0.0", includeDependencies = true).execute()
        }

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        // Within the overview: the side nav names the catalogs too.
        val card = body.substringAfter("data-testid=\"catalog-overview\"").substringAfter("data-catalog=\"letters\"").substringBefore("data-testid=\"catalog-overview-card\"")
        assertThat(card).`as`("it can be changed here: no read-only badge").doesNotContain("catalog-overview-read-only")
        assertThat(card).contains("href=\"/tenants/${tenant.id.value}/catalogs/letters/browse\"")
        assertThat(card).contains("href=\"/tenants/${tenant.id.value}/catalogs/letters/draft\"").contains(">Next release<")
        val redesign = withMediator { ListCatalogReleases(tenant.id, letters).query() }.single { it.name == "Redesign" }.versionId
        assertThat(card).`as`("parallel work, by its name").contains("href=\"/tenants/${tenant.id.value}/catalogs/letters@$redesign/draft\"")
            .contains(">Redesign<").contains("parallel work")
        assertThat(card).contains("1 change not published yet")
        assertThat(card).contains("v1.0.0")
        assertThat(card).contains(">production</a>")
        assertThat(body.substringAfter("data-testid=\"catalog-overview\"").substringAfter("data-catalog=\"system\""))
            .contains("data-testid=\"catalog-overview-read-only\"").contains(">Supplied by Epistola<")
        // There is no page to generate a document from here; a link to one led nowhere.
        assertThat(body).doesNotContain("/tenants/${tenant.id.value}/generate")
    }

    @Test
    fun `the tenant home says which environments make no documents yet, until a version is deployed`() {
        val tenant = createTenant("Home Readiness")
        val production = EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id))
        val test = EnvironmentId(EnvironmentKey.of("test"), TenantId(tenant.id))
        withMediator {
            CreateEnvironment(production, "Production").execute()
            CreateEnvironment(test, "Test").execute()
        }
        fun notice() = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java).body!!
            .substringAfter("data-testid=\"generation-readiness-notice\"", missingDelimiterValue = "")

        assertThat(notice()).contains("Production and Test use no catalog yet, so documents requested there fail.")
            .contains("href=\"/tenants/${tenant.id.value}/environments\"").contains("Deploy a release")

        withMediator { DeployCatalogRelease(production, CatalogKey.of("system"), ListDeployableReleases(tenant.id).query().first { it.catalogKey == CatalogKey.of("system") }.version).execute() }
        assertThat(notice()).contains("Test uses no catalog yet").contains("href=\"/tenants/${tenant.id.value}/environments/test\"")

        withMediator { DeployCatalogRelease(test, CatalogKey.of("system"), ListDeployableReleases(tenant.id).query().first { it.catalogKey == CatalogKey.of("system") }.version).execute() }
        assertThat(notice()).`as`("everything can make documents: no notice").isEmpty()
    }

    @Test
    fun `a new tenant's home walks through the first document, no step done yet`() {
        val tenant = createTenant("Home Getting Started")

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java).body!!

        assertThat(body).contains("data-testid=\"getting-started\"")
        assertThat(body).contains(">Create a template<").contains(">Publish a release<").contains(">Deploy it to an environment<")
        assertThat(body).contains("data-done=\"false\"").doesNotContain("data-done=\"true\"")
        // The steps point at a catalog the tenant has, never at one assumed to exist.
        assertThat(body).contains("/tenants/${tenant.id.value}/catalogs/default/templates")
    }

    @Test
    fun `without a catalog of its own the first step is creating one`() {
        val tenant = createTenant("Home Without Catalogs")
        withMediator { DeleteCatalog(tenant.id, CatalogKey.of("default")).execute() }

        val body = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java).body!!

        assertThat(body).contains(">Create a catalog<").doesNotContain("/catalogs/default/")
    }
}

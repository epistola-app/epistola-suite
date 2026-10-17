// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.versions.CreateCatalogVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.environments.commands.DeployRelease
import app.epistola.suite.mediator.execute
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
    fun `the tenant home shows each catalog's work, open fixes, published version and where it is live`() {
        val tenant = createTenant("Home Overview")
        val letters = CatalogKey.of("letters")
        val catalog = CatalogId(letters, TenantId(tenant.id))
        withMediator {
            CreateCatalog(tenant.id, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
            mediator.releaseNext(catalog)
            CreateCatalogVersion(tenant.id, letters, "2.0.0").execute()
            CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalog), "Seasonal").execute()
            CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
            DeployRelease(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), letters, "1.0.0", includeDependencies = true).execute()
        }

        val response = restTemplate.getForEntity("/tenants/${tenant.id.value}", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        val card = body.substringAfter("data-catalog=\"letters\"").substringBefore("data-testid=\"catalog-overview-card\"")
        assertThat(card).contains(">Authored<")
        assertThat(card).contains("href=\"/tenants/${tenant.id.value}/catalogs/letters/browse\"").contains(">Your work<")
        assertThat(card).`as`("the lower draft is a fix of 1.0.0").contains("href=\"/tenants/${tenant.id.value}/catalogs/letters@1.0.1/browse\"")
            .contains("Fix of v1.0.0 in progress")
        assertThat(card).contains("1 change not published yet")
        assertThat(card).contains("v1.0.0")
        assertThat(card).contains(">production</a>")
        assertThat(body).contains("data-catalog=\"system\"").contains(">Subscribed<")
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.versions.CatalogVersionStatus
import app.epistola.suite.catalog.versions.ListCatalogVersions
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
import app.epistola.suite.mediator.query
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap

/**
 * Something has to be published before the next version is ready (docs/catalog-workspace-navigation.md,
 * use case 3): an in-between version starts from the latest published version, without the unfinished
 * changes, on its own page under a banner, while the next version stays as it is.
 */
class CatalogInBetweenHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    /** Letters 1.0.0 published and live in production; the next version adds a theme. */
    private fun live(name: String): Tenant {
        lateinit var tenant: Tenant
        fixture {
            given {
                tenant = tenant(name)
                withMediator {
                    val catalog = CatalogId(letters, TenantId(tenant.id))
                    CreateCatalog(tenantKey = tenant.id, id = letters, name = "Letters").execute()
                    CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
                    mediator.releaseNext(catalog)
                    CreateTheme(ThemeId(ThemeKey.of("seasonal"), catalog), "Seasonal").execute()
                    CreateEnvironment(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), "Production").execute()
                    DeployRelease(EnvironmentId(EnvironmentKey.of("production"), TenantId(tenant.id)), letters, "1.0.0", includeDependencies = true).execute()
                }
            }
        }
        return tenant
    }

    @Test
    fun `the catalog page offers an in-between version, which opens on its own page while the next version moves out of its way`() {
        val tenant = live("In Between Start")

        val before = get(tenant, "/catalogs/letters/browse").body!!
        assertThat(before).contains("data-testid=\"catalog-in-between-start\"").contains("It starts from v1.0.0, without the changes in the next version")

        val started = post(tenant, "/catalogs/letters/fixes", mapOf("of" to "1.0.0"))
        assertThat(started.statusCode).isEqualTo(HttpStatus.SEE_OTHER)
        assertThat(started.headers.location.toString()).isEqualTo("/tenants/${tenant.id}/catalogs/letters@1.0.1/draft")

        val drafts = withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.DRAFT).query() }
        assertThat(drafts.map { it.version to it.base }).containsExactly("1.1.0" to "1.0.0", "1.0.1" to "1.0.0")

        val page = get(tenant, "/catalogs/letters@1.0.1/draft").body!!
        assertThat(page).contains(">Draft v1.0.1<")
        assertThat(page).contains("data-testid=\"catalog-fix-banner\"").contains("v1.0.0 is live in production")

        val after = get(tenant, "/catalogs/letters/browse").body!!
        assertThat(after).contains("data-testid=\"catalog-in-between-continue\"").doesNotContain("data-testid=\"catalog-in-between-start\"")
        assertThat(after).contains(">Draft v1.0.1<")
    }

    @Test
    fun `starting an in-between version that is already open continues it`() {
        val tenant = live("In Between Continue")
        post(tenant, "/catalogs/letters/fixes", mapOf("of" to "1.0.0"))

        val again = post(tenant, "/catalogs/letters/fixes", mapOf("of" to "1.0.0"))

        assertThat(again.headers.location.toString()).isEqualTo("/tenants/${tenant.id}/catalogs/letters@1.0.1/draft")
        assertThat(withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.DRAFT).query() }).hasSize(2)
    }

    @Test
    fun `a published version's page says where it is live and starts a draft from it, or continues the one open`() {
        val tenant = live("In Between Release Page")

        val before = get(tenant, "/catalogs/letters/releases/1.0.0").body!!
        assertThat(before.substringAfter("data-testid=\"release-live\"").substringBefore("</p>")).contains("Live in <strong>production</strong>")
        assertThat(before).contains("data-testid=\"release-start-draft\"")

        post(tenant, "/catalogs/letters/fixes", mapOf("of" to "1.0.0"))

        val after = get(tenant, "/catalogs/letters/releases/1.0.0").body!!
        assertThat(after).contains("data-testid=\"release-continue-draft\"").contains("Continue draft v1.0.1")
        assertThat(after).doesNotContain("data-testid=\"release-start-draft\"")
    }

    private fun get(tenant: Tenant, path: String) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)
        .getForEntity("/tenants/${tenant.id}$path", String::class.java)

    private fun post(tenant: Tenant, path: String, fields: Map<String, String>) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).postForEntity(
        "/tenants/${tenant.id}$path",
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } },
            HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED },
        ),
        String::class.java,
    )
}

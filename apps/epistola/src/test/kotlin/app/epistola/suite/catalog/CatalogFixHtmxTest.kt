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
 * Fixing a published version from the catalog's History (docs/catalog-workspace-navigation.md): the
 * fix opens as the next patch of that version, under a banner, and your work moves out of its way.
 */
class CatalogFixHtmxTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    private val letters = CatalogKey.of("letters")

    /** Letters 1.0.0 published and live in production; your work is 1.0.1 with a new theme. */
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
    fun `History offers to fix the live version, and the fix opens as its next patch while your work moves up`() {
        val tenant = live("Fix Start")

        val before = browse(tenant, "letters")
        assertThat(before).contains("data-testid=\"catalog-history-fix\"")
        assertThat(before.substringAfter("data-testid=\"catalog-history-live\"").substringBefore("</td>")).contains("production")

        val started = post(tenant, "/catalogs/letters/fixes", mapOf("of" to "1.0.0"))
        assertThat(started.statusCode).isEqualTo(HttpStatus.SEE_OTHER)
        assertThat(started.headers.location.toString()).isEqualTo("/tenants/${tenant.id}/catalogs/letters@1.0.1/browse")

        val drafts = withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.DRAFT).query() }
        assertThat(drafts.map { it.version to it.base }).containsExactly("1.1.0" to "1.0.0", "1.0.1" to "1.0.0")

        val fix = browse(tenant, "letters@1.0.1")
        assertThat(fix).contains("data-testid=\"catalog-fix-banner\"").contains("You are fixing v1.0.0, which is live in production")
        assertThat(fix).contains("<h2>Fix of v1.0.0</h2>")

        val work = browse(tenant, "letters")
        assertThat(work).contains("<h2>Your work</h2>").contains("data-testid=\"catalog-history-continue-fix\"")
        assertThat(work).doesNotContain("data-testid=\"catalog-history-fix\"")
    }

    @Test
    fun `starting a fix that is already open continues it`() {
        val tenant = live("Fix Continue")
        post(tenant, "/catalogs/letters/fixes", mapOf("of" to "1.0.0"))

        val again = post(tenant, "/catalogs/letters/fixes", mapOf("of" to "1.0.0"))

        assertThat(again.headers.location.toString()).isEqualTo("/tenants/${tenant.id}/catalogs/letters@1.0.1/browse")
        assertThat(withMediator { ListCatalogVersions(tenant.id, letters, CatalogVersionStatus.DRAFT).query() }).hasSize(2)
    }

    private fun browse(tenant: Tenant, segment: String): String = restTemplate
        .getForEntity("/tenants/${tenant.id}/catalogs/$segment/browse", String::class.java).body!!

    private fun post(tenant: Tenant, path: String, fields: Map<String, String>) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).postForEntity(
        "/tenants/${tenant.id}$path",
        HttpEntity(
            LinkedMultiValueMap<String, String>().apply { fields.forEach { (k, v) -> add(k, v) } },
            HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED },
        ),
        String::class.java,
    )
}

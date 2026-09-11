// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.suite.BaseIntegrationTest
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

private const val FIXTURE_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

/**
 * Being told about a new release without going looking for it.
 *
 * This is the point of recording what a check found rather than asking while a page renders: the
 * navigation can carry a count on every page, and the home page can name what is waiting, neither
 * of which could make a network call.
 *
 * Uses a URL-subscribed catalog on purpose. The notification is not an Exchange feature — it works
 * for any source, and testing it through the simplest one says so.
 */
class CatalogUpdateNotificationTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `the navigation carries a count once an update is known`() {
        val tenant = subscribedTenant("nav-count")
        recordAvailable(tenant, "99.0.0")

        // Deliberately a page that has nothing to do with catalogs.
        val response = restTemplate.getForEntity("/tenants/$tenant/templates", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("nav-indicator-catalogs")
        assertThat(response.body).contains("catalog has an update available")
    }

    @Test
    fun `no count is shown when nothing is waiting`() {
        val tenant = subscribedTenant("nav-quiet")

        val response = restTemplate.getForEntity("/tenants/$tenant/templates", String::class.java)

        assertThat(response.body).doesNotContain("nav-indicator-catalogs")
    }

    @Test
    fun `the home page names the catalog that has an update`() {
        val tenant = subscribedTenant("home-notice")
        recordAvailable(tenant, "99.0.0")

        val response = restTemplate.getForEntity("/tenants/$tenant", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("catalog-update-notice")
        assertThat(response.body).contains("v99.0.0")
    }

    @Test
    fun `a withdrawn release is called out separately from an available update`() {
        val tenant = subscribedTenant("home-withdrawn")
        recordWithdrawn(tenant)

        val response = restTemplate.getForEntity("/tenants/$tenant", String::class.java)

        assertThat(response.body).contains("catalog-withdrawn-notice")
        // Not folded into the ordinary "update available" count: it is a different kind of fact.
        assertThat(response.body).doesNotContain("catalog-update-notice")
    }

    @Test
    fun `a quiet tenant sees no notice at all`() {
        val tenant = subscribedTenant("home-quiet")

        val response = restTemplate.getForEntity("/tenants/$tenant", String::class.java)

        assertThat(response.body).doesNotContain("catalog-update-notice")
        assertThat(response.body).doesNotContain("catalog-withdrawn-notice")
    }

    private fun subscribedTenant(name: String): TenantKey {
        val tenant = createTenant(name).id
        withMediator {
            RegisterCatalog(tenantKey = tenant, sourceUrl = FIXTURE_CATALOG_URL, authType = AuthType.NONE).execute()
        }
        return tenant
    }

    /**
     * Plants what a background check would have recorded.
     *
     * Raw SQL because there is no command for "a source published something newer": running the
     * real check would need a source that had, which is a fixture this test does not need.
     */
    private fun recordAvailable(tenant: TenantKey, version: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_upstream_checks (tenant_key, catalog_key, available_release_version, last_checked_at)
                VALUES (:t, :c, :v, NOW())
                ON CONFLICT (tenant_key, catalog_key) DO UPDATE SET available_release_version = :v
                """,
            ).bind("t", tenant).bind("c", CatalogKey.of("epistola-demo")).bind("v", version).execute()
        }
    }

    private fun recordWithdrawn(tenant: TenantKey) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_upstream_checks (tenant_key, catalog_key, installed_availability, last_checked_at)
                VALUES (:t, :c, 'WITHDRAWN', NOW())
                ON CONFLICT (tenant_key, catalog_key) DO UPDATE SET installed_availability = 'WITHDRAWN'
                """,
            ).bind("t", tenant).bind("c", CatalogKey.of("epistola-demo")).execute()
        }
    }
}

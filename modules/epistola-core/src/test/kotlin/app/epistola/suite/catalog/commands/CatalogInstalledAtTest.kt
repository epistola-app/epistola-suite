// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val FIXTURE_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

/**
 * `Catalog.installedAt`, surfaced to AI assistants through the MCP server's `CatalogInfo`, is when
 * the release it installed was received (`catalog_installed_releases`, ADR 0036).
 *
 * The timestamps come from the database clock (`NOW()`), not [app.epistola.suite.clock.EpistolaClock],
 * because they describe when a row was written rather than application time; that is why these
 * assert ordering and equality rather than an exact instant.
 */
class CatalogInstalledAtTest : IntegrationTestBase() {

    @Test
    fun `installing a subscribed catalog stamps installed_at, registering it does not`() {
        val tenant = createTenant("Installed At Register")
        val catalog = testCatalog(tenant.id, CatalogKey.of("epistola-demo"))

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = FIXTURE_CATALOG_URL, authType = AuthType.NONE).execute()
            assertThat(GetCatalog(catalog).query()!!.latestReleasedAt).`as`("nothing received yet").isNull()

            InstallFromCatalog(catalog = catalog).execute()
            assertThat(GetCatalog(catalog).query()!!.latestReleasedAt).isNotNull()
        }
    }

    @Test
    fun `upgrading advances installed_at`() {
        val tenant = createTenant("Installed At Upgrade")
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = FIXTURE_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()
            val registered = GetCatalog(testCatalog(tenant.id, catalogKey)).query()!!.latestReleasedAt!!

            UpgradeCatalog(catalog = testCatalog(tenant.id, catalogKey)).execute()

            val upgraded = GetCatalog(testCatalog(tenant.id, catalogKey)).query()!!.latestReleasedAt!!
            assertThat(upgraded).isAfterOrEqualTo(registered)
        }
    }

    @Test
    fun `an authored catalog has no installed_at`() {
        val tenant = createTenant("Installed At Authored")
        val catalogKey = CatalogKey.of("hand-written")

        withMediator {
            CreateCatalog(tenant.id, catalogKey, "Hand written").execute()

            // Nothing installed it — it was authored here, so the column stays null rather than
            // reporting the moment the row happened to be created.
            assertThat(GetCatalog(testCatalog(tenant.id, catalogKey)).query()!!.latestReleasedAt).isNull()
        }
    }
}

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
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

private const val FIXTURE_CATALOG_URL = "classpath:epistola/catalogs/fixture/catalog.json"

/**
 * `catalogs.installed_at` was declared with the original schema, mapped on `Catalog`, and surfaced
 * to AI assistants through the MCP server's `CatalogInfo` — and written by nothing, so every
 * catalog on every installation reported `null`. These pin the three paths that now set it.
 *
 * The timestamps come from the database clock (`NOW()`), not [app.epistola.suite.clock.EpistolaClock],
 * because they describe when a row was written rather than application time; that is why these
 * assert ordering and equality rather than an exact instant.
 */
class CatalogInstalledAtTest : IntegrationTestBase() {

    @Test
    fun `registering a subscribed catalog stamps installed_at`() {
        val tenant = createTenant("Installed At Register")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = FIXTURE_CATALOG_URL, authType = AuthType.NONE).execute()

            val catalog = GetCatalog(tenant.id, CatalogKey.of("epistola-demo")).query()!!
            assertThat(catalog.installedAt).isNotNull()
        }
    }

    @Test
    fun `upgrading advances installed_at`() {
        val tenant = createTenant("Installed At Upgrade")
        val catalogKey = CatalogKey.of("epistola-demo")

        withMediator {
            RegisterCatalog(tenantKey = tenant.id, sourceUrl = FIXTURE_CATALOG_URL, authType = AuthType.NONE).execute()
            InstallFromCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()
            val registered = GetCatalog(tenant.id, catalogKey).query()!!.installedAt!!

            UpgradeCatalog(tenantKey = tenant.id, catalogKey = catalogKey).execute()

            val upgraded = GetCatalog(tenant.id, catalogKey).query()!!.installedAt!!
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
            assertThat(GetCatalog(tenant.id, catalogKey).query()!!.installedAt).isNull()
        }
    }
}

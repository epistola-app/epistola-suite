// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The shared install/no-op/upgrade state machine behind both the system
 * catalog installer and the demo loader. Drives all three transitions using
 * the bundled demo catalog as the subscribed source.
 */
class EnsureSubscribedCatalogTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    private val demoUrl = "classpath:epistola/catalogs/demo/catalog.json"

    @Test
    fun `first sight installs, re-run is a no-op, drifted fingerprint upgrades`() {
        val tenant = createTenant("Ensure Sub")
        val demoKey = CatalogKey.of("epistola-demo")

        withMediator {
            // 1. First sight → INSTALLED
            val first = EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute()
            assertThat(first.status).isEqualTo(EnsureCatalogStatus.INSTALLED)
            assertThat(first.catalogKey).isEqualTo(demoKey)
            assertThat(first.previousVersion).isNull()
            assertThat(first.newVersion).isNotBlank()

            // 2. Re-run, content unchanged → ALREADY_CURRENT
            val second = EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute()
            assertThat(second.status).isEqualTo(EnsureCatalogStatus.ALREADY_CURRENT)
            assertThat(second.newVersion).isEqualTo(first.newVersion)
        }

        // Simulate content drift by stamping a stale installed fingerprint.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                "UPDATE catalogs SET installed_fingerprint = 'stale' WHERE tenant_key = :t AND id = 'epistola-demo'",
            ).bind("t", tenant.id).execute()
        }

        withMediator {
            // 3. Drifted → UPGRADED
            val third = EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = demoUrl).execute()
            assertThat(third.status).isEqualTo(EnsureCatalogStatus.UPGRADED)
            assertThat(third.catalogKey).isEqualTo(demoKey)
            assertThat(third.newVersion).isNotBlank()
        }
    }

    @Test
    fun `manifest without fingerprint falls back to version compare (no re-upgrade loop)`() {
        // dependency-test's manifest has release.version "1.0" and NO
        // release.fingerprint — the legacy/hand-rolled case.
        val tenant = createTenant("Ensure NoFp")
        val noFpUrl = "classpath:test-catalogs/dependency-test/catalog.json"

        withMediator {
            val first = EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = noFpUrl).execute()
            assertThat(first.status).isEqualTo(EnsureCatalogStatus.INSTALLED)
            assertThat(first.catalogKey).isEqualTo(CatalogKey.of("dep-test"))

            // Re-run: no fingerprint to compare → version fallback → no
            // UpgradeCatalog (would loop on every boot before A1).
            val second = EnsureSubscribedCatalog(tenantKey = tenant.id, sourceUrl = noFpUrl).execute()
            assertThat(second.status).isEqualTo(EnsureCatalogStatus.ALREADY_CURRENT)
            assertThat(second.newVersion).isEqualTo(first.newVersion)
        }
    }
}

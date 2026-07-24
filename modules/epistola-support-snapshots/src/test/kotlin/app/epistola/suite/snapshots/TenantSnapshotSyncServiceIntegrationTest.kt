// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.snapshots

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary

/**
 * Exercises the shared sync engine: fingerprint-based dedup (an unchanged tenant uploads nothing)
 * and the last-sync freshness signal that the upgrading timer reads (updated even when the upload
 * is skipped).
 */
@Import(TenantSnapshotSyncServiceIntegrationTest.TestConfig::class)
class TenantSnapshotSyncServiceIntegrationTest : IntegrationTestBase() {
    class TestConfig {
        @Bean
        @Primary
        fun recordingSnapshotSyncPort(): SnapshotSyncPort = RecordingSnapshotSyncPort()
    }

    @Autowired
    lateinit var syncPort: SnapshotSyncPort

    @Autowired
    lateinit var syncService: TenantSnapshotSyncService

    private val recording get() = syncPort as RecordingSnapshotSyncPort

    @BeforeEach
    fun resetRecordingPort() {
        recording.reset()
    }

    @Test
    fun `sync uploads once then skips the upload while catalogs are unchanged`() {
        val tenant = createTenant("Dedup")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }

        val first = runAs(snapshotSystemPrincipal(tenant.id)) { syncService.syncTenant(tenant.id) }
        assertThat(first).isInstanceOf(SnapshotSyncOutcome.Uploaded::class.java)
        assertThat(recording.uploads).hasSize(1)

        val second = runAs(snapshotSystemPrincipal(tenant.id)) { syncService.syncTenant(tenant.id) }
        assertThat(second).isInstanceOf(SnapshotSyncOutcome.Unchanged::class.java)
        // No second upload — the suite-side fingerprint cache short-circuited it.
        assertThat(recording.uploads).hasSize(1)
    }

    @Test
    fun `a new catalog changes the fingerprint and triggers a fresh upload`() {
        val tenant = createTenant("Changed")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }
        runAs(snapshotSystemPrincipal(tenant.id)) { syncService.syncTenant(tenant.id) }

        withMediator { CreateCatalog(tenant.id, CatalogKey.of("beta"), "Beta").execute() }
        val outcome = runAs(snapshotSystemPrincipal(tenant.id)) { syncService.syncTenant(tenant.id) }

        assertThat(outcome).isInstanceOf(SnapshotSyncOutcome.Uploaded::class.java)
        assertThat(recording.uploads).hasSize(2)
    }

    @Test
    fun `lastSnapshotAt is recorded on upload and refreshed even when the upload is skipped`() {
        val tenant = createTenant("Freshness")
        withMediator { CreateCatalog(tenant.id, CatalogKey.of("alpha"), "Alpha").execute() }

        assertThat(syncService.lastSnapshotAt(tenant.id)).isNull()

        runAs(snapshotSystemPrincipal(tenant.id)) { syncService.syncTenant(tenant.id) }
        val afterUpload = syncService.lastSnapshotAt(tenant.id)
        assertThat(afterUpload).isNotNull()

        // An unchanged re-sync uploads nothing but still advances the freshness timestamp.
        runAs(snapshotSystemPrincipal(tenant.id)) { syncService.syncTenant(tenant.id) }
        val afterUnchanged = syncService.lastSnapshotAt(tenant.id)
        assertThat(afterUnchanged).isNotNull()
        assertThat(afterUnchanged!!).isAfterOrEqualTo(afterUpload!!)
        assertThat(recording.uploads).hasSize(1)
    }
}

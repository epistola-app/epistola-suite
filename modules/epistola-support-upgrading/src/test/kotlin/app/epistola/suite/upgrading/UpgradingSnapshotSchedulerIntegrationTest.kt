package app.epistola.suite.upgrading

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.execute
import app.epistola.suite.snapshots.SnapshotSyncPort
import app.epistola.suite.snapshots.TenantSnapshotSyncService
import app.epistola.suite.snapshots.snapshotSystemPrincipal
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.time.Duration
import java.time.Instant

/**
 * The freshness sweep's decision logic: it snapshots a tenant only when the `support-compatibility-
 * check` feature is available **and** no snapshot was synced (by any feature — backups count too)
 * within `epistola.support.upgrading.snapshot.max-age`. The scheduler bean itself is gated on
 * `epistola.support.upgrading.snapshot.scheduled.enabled`, so the test constructs it directly
 * against the context's real [TenantSnapshotSyncService] and a recording port.
 */
@Import(UpgradingSnapshotSchedulerIntegrationTest.TestConfig::class)
class UpgradingSnapshotSchedulerIntegrationTest : IntegrationTestBase() {
    class TestConfig {
        @Bean
        @Primary
        fun recordingSnapshotSyncPort(): SnapshotSyncPort = RecordingSnapshotSyncPort()
    }

    @Autowired
    private lateinit var syncPort: SnapshotSyncPort

    @Autowired
    private lateinit var syncService: TenantSnapshotSyncService

    @Autowired
    private lateinit var jdbi: Jdbi

    private val recording get() = syncPort as RecordingSnapshotSyncPort

    @BeforeEach
    fun resetRecordingPort() {
        recording.reset()
    }

    private fun scheduler(maxAge: Duration = Duration.ofHours(24)) = UpgradingSnapshotScheduler(
        snapshotSync = syncService,
        mediator = mediator,
        properties = UpgradingSnapshotProperties(maxAge = maxAge),
        jdbi = jdbi,
        cron = "0 0 * * * *",
    )

    private fun enableCompatibilityCheck(tenantKey: TenantKey): Unit = withMediator {
        SaveFeatureToggle(
            tenantKey = tenantKey,
            featureKey = KnownFeatures.SUPPORT_COMPATIBILITY_CHECK,
            enabled = true,
        ).execute()
    }

    private fun lastSnapshotAt(tenantKey: TenantKey): Instant? = syncService.lastSnapshotAt(tenantKey)

    @Test
    fun `sweep snapshots a toggled-on tenant that has no prior snapshot`() {
        val tenant = createTenant("Upgrading Sweep Fresh")
        enableCompatibilityCheck(tenant.id)

        scheduler().ensureFreshSnapshots()

        assertThat(recording.uploadsFor(tenant.id)).hasSize(1)
        assertThat(lastSnapshotAt(tenant.id)).isEqualTo(testClock.instant())
    }

    @Test
    fun `sweep skips a tenant whose snapshot is fresher than max-age`() {
        val tenant = createTenant("Upgrading Sweep Skip")
        enableCompatibilityCheck(tenant.id)

        scheduler().ensureFreshSnapshots()
        val firstSyncAt = lastSnapshotAt(tenant.id)
        assertThat(firstSyncAt).isEqualTo(testClock.instant())

        // One hour later the snapshot is still fresh (max-age 24h): the sweep must do nothing —
        // no new upload and, crucially, no re-stamp of the last-sync timestamp.
        testClock.advanceBy(Duration.ofHours(1))
        scheduler().ensureFreshSnapshots()

        assertThat(recording.uploadsFor(tenant.id)).hasSize(1)
        assertThat(lastSnapshotAt(tenant.id)).isEqualTo(firstSyncAt)
    }

    @Test
    fun `sweep refreshes a tenant whose last sync is older than max-age`() {
        val tenant = createTenant("Upgrading Sweep Stale")
        enableCompatibilityCheck(tenant.id)

        scheduler().ensureFreshSnapshots()
        val firstSyncAt = lastSnapshotAt(tenant.id)

        // Past max-age the sweep syncs again. The catalogs are unchanged, so the sync dedups the
        // upload but confirms freshness by re-stamping the last-sync timestamp to "now".
        testClock.advanceBy(Duration.ofHours(25))
        scheduler().ensureFreshSnapshots()

        assertThat(lastSnapshotAt(tenant.id)).isEqualTo(testClock.instant())
        assertThat(lastSnapshotAt(tenant.id)).isNotEqualTo(firstSyncAt)
    }

    @Test
    fun `sweep skips tenants without the compatibility-check feature`() {
        val tenant = createTenant("Upgrading Sweep Toggle Off")

        scheduler().ensureFreshSnapshots()

        assertThat(recording.uploadsFor(tenant.id)).isEmpty()
        assertThat(lastSnapshotAt(tenant.id)).isNull()
    }

    @Test
    fun `a snapshot synced by another feature counts as fresh`() {
        val tenant = createTenant("Upgrading Sweep Shared")
        enableCompatibilityCheck(tenant.id)

        // Another feature (e.g. the daily backup) syncs a snapshot through the shared service.
        runAs(snapshotSystemPrincipal(tenant.id)) {
            syncService.syncTenant(tenant.id)
        }
        val backupSyncAt = lastSnapshotAt(tenant.id)
        assertThat(recording.uploadsFor(tenant.id)).hasSize(1)

        // The upgrading sweep reads the shared last-sync timestamp and stays dormant.
        testClock.advanceBy(Duration.ofHours(1))
        scheduler().ensureFreshSnapshots()

        assertThat(recording.uploadsFor(tenant.id)).hasSize(1)
        assertThat(lastSnapshotAt(tenant.id)).isEqualTo(backupSyncAt)
    }
}

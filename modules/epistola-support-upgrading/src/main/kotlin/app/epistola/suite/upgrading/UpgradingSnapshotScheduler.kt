package app.epistola.suite.upgrading

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.scheduling.SchedulerLock
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.snapshots.TenantSnapshotSyncService
import app.epistola.suite.snapshots.snapshotSystemPrincipal
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Keeps a current snapshot available for the compatibility ("Upgrading") check. For every tenant
 * with the `support-upgrading` toggle on, it makes a snapshot **only when none was made in the last
 * [UpgradingSnapshotProperties.maxAge]** — backup-made snapshots count too, so when a tenant also
 * has backups on this sweep does nothing and the daily backup carries the freshness.
 *
 * Runs hourly by default; only active when `epistola.support.upgrading.snapshot.scheduled.enabled=true`.
 * In a multi-pod deployment [SchedulerLock] ensures only one instance runs per cycle. Each tenant is
 * processed under a system principal so the permission-gated snapshot build authorizes; a failure on
 * one tenant is logged and does not stop the others.
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(UpgradingSnapshotProperties::class)
@ConditionalOnProperty(
    name = ["epistola.support.upgrading.snapshot.scheduled.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class UpgradingSnapshotScheduler(
    private val snapshotSync: TenantSnapshotSyncService,
    private val mediator: Mediator,
    private val schedulerLock: SchedulerLock,
    private val featureToggleService: FeatureToggleService,
    private val properties: UpgradingSnapshotProperties,
    private val jdbi: Jdbi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${epistola.support.upgrading.snapshot.cron:0 0 * * * *}")
    fun ensureFreshSnapshots() {
        schedulerLock.runExclusively(SchedulerLock.UPGRADING_SNAPSHOT) {
            MediatorContext.runWithMediator(mediator) {
                ensureAllTenants()
            }
        }
    }

    private fun ensureAllTenants() {
        val tenantKeys = allTenantKeys()
        for (tenantKey in tenantKeys) {
            if (!featureToggleService.isEnabled(tenantKey, KnownFeatures.SUPPORT_UPGRADING)) continue
            if (hasFreshSnapshot(tenantKey)) continue
            try {
                SecurityContext.runWithPrincipal(snapshotSystemPrincipal(tenantKey)) {
                    snapshotSync.syncTenant(tenantKey)
                }
            } catch (e: Exception) {
                log.error("Upgrading snapshot refresh failed for tenant {}: {}", tenantKey.value, e.message, e)
            }
        }
    }

    /** True when a snapshot was synced (by any feature) within [UpgradingSnapshotProperties.maxAge]. */
    private fun hasFreshSnapshot(tenantKey: TenantKey): Boolean {
        val last = snapshotSync.lastSnapshotAt(tenantKey) ?: return false
        return Duration.between(last, Instant.now()) < properties.maxAge
    }

    private fun allTenantKeys(): List<TenantKey> = jdbi.withHandle<List<TenantKey>, Exception> { handle ->
        handle
            .createQuery("SELECT id FROM tenants ORDER BY id")
            .mapTo(String::class.java)
            .list()
            .map { TenantKey.of(it) }
    }
}

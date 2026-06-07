package app.epistola.suite.backups

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.FeatureToggleService
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.scheduling.SchedulerLock
import app.epistola.suite.security.SecurityContext
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Runs the daily catalog backup for every tenant that has the `support-backups` feature toggle
 * enabled. Only active when `epistola.support.backups.scheduled.enabled=true`.
 *
 * In a multi-pod deployment every instance schedules this; [SchedulerLock] ensures only one runs
 * per cycle. Each tenant is processed under a system principal so the permission-gated snapshot
 * build authorizes; a failure on one tenant is logged and does not stop the others.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(
    name = ["epistola.support.backups.scheduled.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class BackupScheduler(
    private val backupService: CatalogBackupService,
    private val mediator: Mediator,
    private val schedulerLock: SchedulerLock,
    private val featureToggleService: FeatureToggleService,
    private val jdbi: Jdbi,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${epistola.support.backups.cron:0 0 2 * * *}")
    fun runDailyBackup() {
        schedulerLock.runExclusively(SchedulerLock.CATALOG_BACKUP) {
            MediatorContext.runWithMediator(mediator) {
                backupAllTenants()
            }
        }
    }

    private fun backupAllTenants() {
        val tenantKeys = allTenantKeys()
        log.info("Daily catalog backup starting for {} tenant(s)", tenantKeys.size)
        for (tenantKey in tenantKeys) {
            // Snapshots feed both Backups and Upgrading (compatibility), so upload when either is on.
            val needsSnapshot =
                featureToggleService.isEnabled(tenantKey, KnownFeatures.SUPPORT_BACKUPS) ||
                    featureToggleService.isEnabled(tenantKey, KnownFeatures.SUPPORT_UPGRADING)
            if (!needsSnapshot) continue
            try {
                SecurityContext.runWithPrincipal(backupSystemPrincipal(tenantKey)) {
                    backupService.backupTenant(tenantKey)
                }
            } catch (e: Exception) {
                log.error("Catalog backup failed for tenant {}: {}", tenantKey.value, e.message, e)
            }
        }
    }

    private fun allTenantKeys(): List<TenantKey> = jdbi.withHandle<List<TenantKey>, Exception> { handle ->
        handle
            .createQuery("SELECT id FROM tenants ORDER BY id")
            .mapTo(String::class.java)
            .list()
            .map { TenantKey.of(it) }
    }
}

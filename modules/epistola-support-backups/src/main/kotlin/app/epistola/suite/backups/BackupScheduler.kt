package app.epistola.suite.backups

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.support.isHubUnreachable
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Runs the daily faithful backup for every tenant that has the `support-backups` feature available.
 * Only active when `epistola.support.backups.scheduled.enabled=true`.
 *
 * The native scheduled-task lease ensures only one node runs each cycle. Each tenant is processed
 * under a system principal so the permission-gated backup build authorizes; a failure on one
 * tenant is logged and does not stop the others.
 */
@Component
@ConditionalOnProperty(
    name = ["epistola.support.backups.scheduled.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class BackupScheduler(
    private val backupService: TenantBackupService,
    private val mediator: Mediator,
    private val jdbi: Jdbi,
    @Value("\${epistola.support.backups.cron:0 0 2 * * *}")
    private val cron: String,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun backupScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(cron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        runDailyBackup()
    }

    fun runDailyBackup() {
        MediatorContext.runWithMediator(mediator) { backupAllTenants() }
    }

    private fun backupAllTenants() {
        val tenantKeys = allTenantKeys()
        log.info("Daily backup starting for {} tenant(s)", tenantKeys.size)
        for (tenantKey in tenantKeys) {
            // Skip a tenant unless the Backups feature is available, so we never do work that would
            // only be rejected. Backups are stored locally; Upgrading keeps its own export snapshot.
            if (ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.SUPPORT_BACKUPS] != true) continue
            try {
                SecurityContext.runWithPrincipal(SystemUser.principalForTenant(tenantKey)) {
                    backupService.backupTenant(tenantKey)
                }
            } catch (e: Exception) {
                // Back off: if the hub is down the remaining tenants would all fail their upload too.
                // Log one warning and stop the sweep — the next daily run retries from scratch.
                if (e.isHubUnreachable()) {
                    log.warn("Epistola hub unreachable; stopping the backup sweep this cycle: {}", e.message)
                    break
                }
                log.error("Backup failed for tenant {}: {}", tenantKey.value, e.message, e)
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

    companion object {
        const val TASK_KEY = "support.backups.daily"
        const val ROUTING_KEY = "system:support.backups.daily"
        const val TASK_TYPE = "support.backups.daily"
    }
}

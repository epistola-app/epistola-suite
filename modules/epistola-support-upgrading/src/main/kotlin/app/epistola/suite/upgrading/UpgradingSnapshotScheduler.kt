// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.upgrading

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
import app.epistola.suite.snapshots.TenantSnapshotSyncService
import app.epistola.suite.snapshots.snapshotSystemPrincipal
import app.epistola.suite.support.isHubUnreachable
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Keeps a current snapshot available for the compatibility ("Upgrading") check. For every tenant
 * with the `support-upgrading` toggle on, it makes a snapshot **only when none was made in the last
 * [UpgradingSnapshotProperties.maxAge]** — backup-made snapshots count too, so when a tenant also
 * has backups on this sweep does nothing and the daily backup carries the freshness.
 *
 * Runs hourly by default; only active when `epistola.support.upgrading.snapshot.scheduled.enabled=true`.
 * The native scheduled-task lease ensures only one node runs each cycle. Each tenant is processed
 * under a system principal so the permission-gated snapshot build authorizes; a failure on one tenant
 * is logged and does not stop the others.
 */
@Component
@EnableConfigurationProperties(UpgradingSnapshotProperties::class)
@ConditionalOnProperty(
    name = ["epistola.support.upgrading.snapshot.scheduled.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class UpgradingSnapshotScheduler(
    private val snapshotSync: TenantSnapshotSyncService,
    private val mediator: Mediator,
    private val properties: UpgradingSnapshotProperties,
    private val jdbi: Jdbi,
    @Value("\${epistola.support.upgrading.snapshot.cron:0 0 * * * *}")
    private val cron: String,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    override val taskType: String = TASK_TYPE

    @Bean
    fun upgradingSnapshotScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(cron),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        ensureFreshSnapshots()
    }

    fun ensureFreshSnapshots() {
        MediatorContext.runWithMediator(mediator) { ensureAllTenants() }
    }

    private fun ensureAllTenants() {
        val tenantKeys = allTenantKeys()
        for (tenantKey in tenantKeys) {
            if (ResolveAvailableFeatures(tenantKey).query()[KnownFeatures.SUPPORT_COMPATIBILITY_CHECK] != true) continue
            if (hasFreshSnapshot(tenantKey)) continue
            try {
                SecurityContext.runWithPrincipal(snapshotSystemPrincipal(tenantKey)) {
                    snapshotSync.syncTenant(tenantKey)
                }
            } catch (e: Exception) {
                // Back off: the hub is down, so the remaining tenants would all fail their upload
                // too. Log one warning and stop the sweep — the next run retries from scratch.
                if (e.isHubUnreachable()) {
                    log.warn("Epistola hub unreachable; stopping upgrading snapshot sweep this cycle: {}", e.message)
                    break
                }
                log.error("Upgrading snapshot refresh failed for tenant {}: {}", tenantKey.value, e.message, e)
            }
        }
    }

    /** True when a snapshot was synced (by any feature) within [UpgradingSnapshotProperties.maxAge]. */
    private fun hasFreshSnapshot(tenantKey: TenantKey): Boolean {
        val last = snapshotSync.lastSnapshotAt(tenantKey) ?: return false
        return Duration.between(last, EpistolaClock.instant()) < properties.maxAge
    }

    private fun allTenantKeys(): List<TenantKey> = jdbi.withHandle<List<TenantKey>, Exception> { handle ->
        handle
            .createQuery("SELECT id FROM tenants ORDER BY id")
            .mapTo(String::class.java)
            .list()
            .map { TenantKey.of(it) }
    }

    companion object {
        const val TASK_KEY = "support.upgrading.snapshot-freshness"
        const val ROUTING_KEY = "system:support.upgrading.snapshot-freshness"
        const val TASK_TYPE = "support.upgrading.snapshot-freshness"
    }
}

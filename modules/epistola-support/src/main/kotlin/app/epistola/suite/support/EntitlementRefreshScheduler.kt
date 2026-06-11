package app.epistola.suite.support

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Backstop refresh of the hub entitlement set. Changes normally propagate near-instantly via the
 * revision header the hub stamps on every response (see [EntitlementRevisionTrigger]), and the
 * initial fetch happens right after registration; this poll is the safety net for an installation
 * with no other hub traffic and for recovering after an outage — hence a long default (6h).
 * Last-known-good is preserved on failure. This is an all-node scheduled task and each node refreshes
 * the shared `app_metadata` row idempotently. Active only when the support tier is enabled.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class EntitlementRefreshScheduler(
    private val entitlementSync: EntitlementSyncService,
    @Value("\${epistola.support.entitlements.refresh-ms:21600000}")
    private val refreshMs: Long,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun entitlementRefreshScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(refreshMs),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    override fun handle(task: ClusterScheduledTask) {
        refresh()
    }

    fun refresh() {
        entitlementSync.refresh()
    }

    companion object {
        const val TASK_KEY = "support.entitlements.refresh"
        const val ROUTING_KEY = "system:support.entitlements.refresh"
        const val TASK_TYPE = "support.entitlements.refresh"
    }
}

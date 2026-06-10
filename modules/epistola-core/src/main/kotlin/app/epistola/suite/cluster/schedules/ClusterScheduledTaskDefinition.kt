package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.common.ids.TenantKey

/**
 * Code-owned definition for a recurring cluster scheduled task.
 *
 * Definitions are registered on startup by [ClusterScheduledTaskRegistrar] and
 * upserted into `cluster_tasks_scheduled`. Code remains the source of truth for
 * schedule shape, routing, capability, payload, and policies; the database row
 * is the durable runtime state that stores the next due time, lease, and
 * failure metadata.
 *
 * Use a stable [taskKey] across deployments. Changing the schedule shape resets
 * the next due time; changing payload, capability, routing, or enabled state
 * updates the row without losing execution history.
 */
data class ClusterScheduledTaskDefinition(
    val taskKey: String,
    val routingKey: String,
    val taskType: String,
    val schedule: ClusterScheduledTaskSchedule,
    val requiredCapability: String = ClusterProperties.DEFAULT_CAPABILITY,
    val payload: Map<String, Any?> = emptyMap(),
    val tenantKey: TenantKey? = null,
    val zoneId: String = "UTC",
    val failurePolicy: ClusterScheduledTaskFailurePolicy = ClusterScheduledTaskFailurePolicy.RETRY_SAME_DUE,
    val catchUpPolicy: ClusterScheduledTaskCatchUpPolicy = ClusterScheduledTaskCatchUpPolicy.COALESCE,
    val enabled: Boolean = true,
)

/**
 * Supported recurring schedule expressions.
 *
 * `Cron` uses Spring's cron parser in the definition's `zoneId`. `FixedDelay`
 * waits the interval after a successful run completes. `FixedRate` advances by
 * interval from the previous due time and is affected by catch-up policy.
 */
sealed interface ClusterScheduledTaskSchedule {
    /**
     * Cron expression evaluated in the task definition's zone.
     */
    data class Cron(val expression: String) : ClusterScheduledTaskSchedule

    /**
     * Interval in milliseconds measured from successful completion.
     */
    data class FixedDelay(val intervalMs: Long) : ClusterScheduledTaskSchedule

    /**
     * Interval in milliseconds measured from the previous due time.
     */
    data class FixedRate(val intervalMs: Long) : ClusterScheduledTaskSchedule
}

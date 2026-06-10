package app.epistola.suite.cluster

import app.epistola.suite.common.ids.TenantKey

data class ClusterScheduledTaskDefinition(
    val taskKey: String,
    val routingKey: String,
    val taskType: String,
    val schedule: ClusterScheduledTaskSchedule,
    val payload: Map<String, Any?> = emptyMap(),
    val tenantKey: TenantKey? = null,
    val zoneId: String = "UTC",
    val failurePolicy: ClusterScheduledTaskFailurePolicy = ClusterScheduledTaskFailurePolicy.RETRY_SAME_DUE,
    val catchUpPolicy: ClusterScheduledTaskCatchUpPolicy = ClusterScheduledTaskCatchUpPolicy.COALESCE,
    val enabled: Boolean = true,
)

sealed interface ClusterScheduledTaskSchedule {
    data class Cron(val expression: String) : ClusterScheduledTaskSchedule
    data class FixedDelay(val intervalMs: Long) : ClusterScheduledTaskSchedule
    data class FixedRate(val intervalMs: Long) : ClusterScheduledTaskSchedule
}

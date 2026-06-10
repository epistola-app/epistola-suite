package app.epistola.suite.cluster.schedules

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class ClusterScheduledTask(
    val taskKey: String,
    val tenantKey: TenantKey?,
    val routingKey: String,
    val taskType: String,
    val requiredCapability: String,
    val payload: Map<String, Any?>,
    val scheduleKind: ClusterScheduledTaskScheduleKind,
    val cronExpression: String?,
    val intervalMs: Long?,
    val zoneId: String,
    val failurePolicy: ClusterScheduledTaskFailurePolicy,
    val catchUpPolicy: ClusterScheduledTaskCatchUpPolicy,
    val enabled: Boolean,
    val nextDueAt: OffsetDateTime,
    val leaseOwnerNodeId: String?,
    val leaseExpiresAt: OffsetDateTime?,
    val lastStartedAt: OffsetDateTime?,
    val lastCompletedAt: OffsetDateTime?,
    val lastFailedAt: OffsetDateTime?,
    val attemptCount: Int,
    val consecutiveFailures: Int,
    val lastError: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

enum class ClusterScheduledTaskScheduleKind(val dbValue: String) {
    CRON("cron"),
    FIXED_DELAY("fixed_delay"),
    FIXED_RATE("fixed_rate"),
    ;

    companion object {
        fun fromDb(value: String): ClusterScheduledTaskScheduleKind = entries.firstOrNull { it.dbValue == value }
            ?: error("Unknown cluster scheduled task schedule kind '$value'")
    }
}

enum class ClusterScheduledTaskFailurePolicy(val dbValue: String) {
    RETRY_SAME_DUE("retry_same_due"),
    ADVANCE_ON_FAILURE("advance_on_failure"),
    ;

    companion object {
        fun fromDb(value: String): ClusterScheduledTaskFailurePolicy = entries.firstOrNull { it.dbValue == value }
            ?: error("Unknown cluster scheduled task failure policy '$value'")
    }
}

enum class ClusterScheduledTaskCatchUpPolicy(val dbValue: String) {
    COALESCE("coalesce"),
    CATCH_UP("catch_up"),
    ;

    companion object {
        fun fromDb(value: String): ClusterScheduledTaskCatchUpPolicy = entries.firstOrNull { it.dbValue == value }
            ?: error("Unknown cluster scheduled task catch-up policy '$value'")
    }
}

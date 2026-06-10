package app.epistola.suite.cluster.schedules

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

/**
 * Durable runtime state for a recurring scheduled task.
 *
 * Scheduled tasks are different from one-shot timers: the row is the recurring
 * definition and execution state, not a single occurrence. The scheduler claims
 * due rows, dispatches them to [ClusterScheduledTaskHandler], and then advances
 * `nextDueAt` according to the schedule shape and failure/catch-up policies.
 *
 * `taskKey` is the stable definition key. `routingKey` controls node affinity,
 * `taskType` is the handler dispatch key, and `requiredCapability` restricts
 * execution to capable active nodes. `tenantKey == null` means the task is
 * system-scoped.
 */
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

/**
 * Persisted schedule shape for a recurring task.
 */
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

/**
 * Policy applied after a handler failure.
 *
 * `RETRY_SAME_DUE` keeps the failed occurrence conceptually current and moves
 * `nextDueAt` to the retry time. `ADVANCE_ON_FAILURE` skips to the next normal
 * occurrence.
 */
enum class ClusterScheduledTaskFailurePolicy(val dbValue: String) {
    RETRY_SAME_DUE("retry_same_due"),
    ADVANCE_ON_FAILURE("advance_on_failure"),
    ;

    companion object {
        fun fromDb(value: String): ClusterScheduledTaskFailurePolicy = entries.firstOrNull { it.dbValue == value }
            ?: error("Unknown cluster scheduled task failure policy '$value'")
    }
}

/**
 * Policy for missed fixed-rate or cron occurrences.
 *
 * `COALESCE` skips overdue occurrences and schedules the next future run.
 * `CATCH_UP` advances one occurrence at a time until the task has caught up.
 */
enum class ClusterScheduledTaskCatchUpPolicy(val dbValue: String) {
    COALESCE("coalesce"),
    CATCH_UP("catch_up"),
    ;

    companion object {
        fun fromDb(value: String): ClusterScheduledTaskCatchUpPolicy = entries.firstOrNull { it.dbValue == value }
            ?: error("Unknown cluster scheduled task catch-up policy '$value'")
    }
}

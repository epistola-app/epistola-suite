package app.epistola.suite.cluster.timers

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

/**
 * Durable row-backed representation of a one-shot cluster timer.
 *
 * A timer is created through [ScheduleClusterTimer], claimed by
 * [ClusterTimerScheduler], and dispatched to exactly one capable active node at
 * a time by combining routing-key affinity with a PostgreSQL row lease. The
 * execution model is still at-least-once: if a node performs side effects and
 * crashes before completing the row, another node may reclaim the timer after
 * the lease expires.
 *
 * `timerKey` is the stable idempotency key. `routingKey` controls node
 * affinity and should usually be a tenant/domain-model key. `timerType` is the
 * handler dispatch key and must match exactly one [ClusterTimerHandler].
 * `tenantKey == null` represents system-scoped work.
 */
data class ClusterTimer(
    val timerKey: String,
    val tenantKey: TenantKey?,
    val routingKey: String,
    val timerType: String,
    val requiredCapability: String,
    val dueAt: OffsetDateTime,
    val payload: Map<String, Any?>,
    val status: ClusterTimerStatus,
    val leaseOwnerNodeId: String?,
    val leaseExpiresAt: OffsetDateTime?,
    val lastStartedAt: OffsetDateTime?,
    val lastCompletedAt: OffsetDateTime?,
    val attemptCount: Int,
    val lastError: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

/**
 * Storage status for a one-shot timer.
 *
 * `SCHEDULED` rows are eligible once `dueAt` has passed. `RUNNING` rows have an
 * active or expired lease. `PAUSED` is reserved for operational controls and is
 * not currently produced by the scheduler.
 */
enum class ClusterTimerStatus(val dbValue: String) {
    SCHEDULED("scheduled"),
    RUNNING("running"),
    PAUSED("paused"),
    ;

    companion object {
        fun fromDb(value: String): ClusterTimerStatus = entries.firstOrNull { it.dbValue == value }
            ?: error("Unknown cluster timer status: $value")
    }
}

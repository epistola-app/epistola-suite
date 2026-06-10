package app.epistola.suite.cluster

import app.epistola.suite.common.ids.TenantKey
import java.time.OffsetDateTime

data class ClusterTimer(
    val timerKey: String,
    val tenantKey: TenantKey?,
    val routingKey: String,
    val timerType: String,
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

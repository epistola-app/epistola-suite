package app.epistola.suite.cluster.timers

import java.time.OffsetDateTime

interface ClusterTimerHandler {
    val timerType: String

    fun handle(timer: ClusterTimer): ClusterTimerResult
}

sealed interface ClusterTimerResult {
    data object Complete : ClusterTimerResult

    data class Reschedule(
        val nextDueAt: OffsetDateTime,
        val payload: Map<String, Any?>? = null,
    ) : ClusterTimerResult
}

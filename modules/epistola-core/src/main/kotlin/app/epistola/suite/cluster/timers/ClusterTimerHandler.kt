// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster.timers

import java.time.OffsetDateTime

/**
 * Handler contract for one-shot cluster timers.
 *
 * Implementations are Spring beans discovered by [ClusterTimerScheduler]. The
 * [timerType] is a stable string contract, not a class name; it must match the
 * value written by [ScheduleClusterTimer]. Handlers must be idempotent because
 * timers are leased and retried with at-least-once execution semantics.
 */
interface ClusterTimerHandler {
    val timerType: String

    fun handle(timer: ClusterTimer): ClusterTimerResult
}

/**
 * Result returned by a [ClusterTimerHandler] after it has handled a claimed
 * timer.
 *
 * Returning [Complete] deletes the timer row. Returning [Reschedule] keeps the
 * same timer key and schedules it for another due time, optionally replacing
 * its payload. Throwing from a handler records the failure and retries after
 * the configured retry delay.
 */
sealed interface ClusterTimerResult {
    /**
     * Marks the timer as finished and removes it from durable storage.
     */
    data object Complete : ClusterTimerResult

    /**
     * Keeps the timer durable and makes it due again at [nextDueAt].
     */
    data class Reschedule(
        val nextDueAt: OffsetDateTime,
        val payload: Map<String, Any?>? = null,
    ) : ClusterTimerResult
}

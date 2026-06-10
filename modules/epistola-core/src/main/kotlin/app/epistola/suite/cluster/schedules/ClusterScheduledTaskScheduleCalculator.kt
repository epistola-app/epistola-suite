package app.epistola.suite.cluster.schedules

import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Computes initial, successful, and failed next-due timestamps for scheduled
 * tasks.
 *
 * The calculator is intentionally separate from persistence and dispatch so the
 * recurrence rules can be tested and evolved independently. It owns the
 * semantics of fixed delay, fixed rate, cron time zones, catch-up/coalescing,
 * and retry backoff. Callers pass persisted task state so the calculation can
 * distinguish the previous due time from wall-clock `now`.
 */
@Component
class ClusterScheduledTaskScheduleCalculator(
    private val clock: Clock,
) {

    /**
     * Computes the first due time when a code definition is registered.
     */
    fun initialDueAt(definition: ClusterScheduledTaskDefinition, now: OffsetDateTime = OffsetDateTime.now(clock)): OffsetDateTime = when (val schedule = definition.schedule) {
        is ClusterScheduledTaskSchedule.Cron -> nextCron(schedule.expression, definition.zoneId, now)
        is ClusterScheduledTaskSchedule.FixedDelay -> now.plusNanos(schedule.intervalMs * NANOS_PER_MILLI)
        is ClusterScheduledTaskSchedule.FixedRate -> now.plusNanos(schedule.intervalMs * NANOS_PER_MILLI)
    }

    /**
     * Computes the next due time after a handler has completed successfully.
     */
    fun nextAfterSuccess(task: ClusterScheduledTask, now: OffsetDateTime = OffsetDateTime.now(clock)): OffsetDateTime = when (task.scheduleKind) {
        ClusterScheduledTaskScheduleKind.CRON -> nextCron(task.cronExpression ?: error("Cron task '${task.taskKey}' has no cron expression"), task.zoneId, baseAfterSuccess(task, now))
        ClusterScheduledTaskScheduleKind.FIXED_DELAY -> now.plusNanos(requiredInterval(task) * NANOS_PER_MILLI)
        ClusterScheduledTaskScheduleKind.FIXED_RATE -> nextFixedRate(task, now)
    }

    /**
     * Computes the next due time after a handler failure, applying the task's
     * failure policy and exponential retry backoff when retrying the same due
     * occurrence.
     */
    fun nextAfterFailure(task: ClusterScheduledTask, now: OffsetDateTime = OffsetDateTime.now(clock), retryDelayMs: Long, maxRetryDelayMs: Long): OffsetDateTime = when (task.failurePolicy) {
        ClusterScheduledTaskFailurePolicy.ADVANCE_ON_FAILURE -> nextAfterSuccess(task, now)
        ClusterScheduledTaskFailurePolicy.RETRY_SAME_DUE -> now.plusNanos(backoffMs(task, retryDelayMs, maxRetryDelayMs) * NANOS_PER_MILLI)
    }

    private fun nextFixedRate(task: ClusterScheduledTask, now: OffsetDateTime): OffsetDateTime {
        val interval = Duration.ofMillis(requiredInterval(task))
        var next = task.nextDueAt.plus(interval)
        if (task.catchUpPolicy == ClusterScheduledTaskCatchUpPolicy.COALESCE) {
            while (!next.isAfter(now)) {
                next = next.plus(interval)
            }
        }
        return next
    }

    private fun baseAfterSuccess(task: ClusterScheduledTask, now: OffsetDateTime): OffsetDateTime {
        if (task.catchUpPolicy == ClusterScheduledTaskCatchUpPolicy.CATCH_UP) {
            return task.nextDueAt
        }
        return if (task.nextDueAt.isAfter(now)) task.nextDueAt else now
    }

    private fun nextCron(expression: String, zoneId: String, after: OffsetDateTime): OffsetDateTime {
        val zone = ZoneId.of(zoneId)
        val next = CronExpression.parse(expression).next(after.atZoneSameInstant(zone))
            ?: error("Cron expression '$expression' did not produce a next occurrence")
        return next.toOffsetDateTime()
    }

    private fun requiredInterval(task: ClusterScheduledTask): Long = task.intervalMs
        ?: error("Scheduled task '${task.taskKey}' has no interval")

    private fun backoffMs(task: ClusterScheduledTask, retryDelayMs: Long, maxRetryDelayMs: Long): Long {
        val shift = task.consecutiveFailures.coerceAtMost(MAX_BACKOFF_SHIFT)
        val multiplier = 1L shl shift
        return (retryDelayMs * multiplier).coerceAtMost(maxRetryDelayMs)
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val MAX_BACKOFF_SHIFT = 10
    }
}

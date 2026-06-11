package app.epistola.suite.cluster.schedules

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class ClusterScheduledTaskScheduleCalculatorTest {
    private val calculator = ClusterScheduledTaskScheduleCalculator()

    @Test
    fun `daily cron initial due uses the next future occurrence when today's time has passed`() {
        val now = OffsetDateTime.parse("2026-06-10T15:30:00Z")

        val nextDueAt = calculator.initialDueAt(
            ClusterScheduledTaskDefinition(
                taskKey = "daily",
                routingKey = "daily",
                taskType = "daily",
                schedule = ClusterScheduledTaskSchedule.Cron("0 0 2 * * ?"),
                zoneId = "UTC",
            ),
            now = now,
        )

        assertThat(nextDueAt).isEqualTo(OffsetDateTime.parse("2026-06-11T02:00:00Z"))
        assertThat(nextDueAt).isAfter(now)
    }

    @Test
    fun `fixed delay initial due is one interval from now`() {
        val now = OffsetDateTime.parse("2026-06-10T15:30:00Z")

        val nextDueAt = calculator.initialDueAt(
            definition(ClusterScheduledTaskSchedule.FixedDelay(60_000)),
            now = now,
        )

        assertThat(nextDueAt).isEqualTo(now.plusSeconds(60))
    }

    @Test
    fun `fixed delay next-after-success measures from now, not the previous due time`() {
        val nextDueAt = OffsetDateTime.parse("2026-06-10T12:00:00Z")
        val now = OffsetDateTime.parse("2026-06-10T12:00:05Z") // handler took 5s

        val next = calculator.nextAfterSuccess(
            task(ClusterScheduledTaskScheduleKind.FIXED_DELAY, nextDueAt = nextDueAt, intervalMs = 60_000),
            now = now,
        )

        assertThat(next).isEqualTo(now.plusSeconds(60))
    }

    @Test
    fun `fixed rate coalesce skips every overdue occurrence to the next future slot`() {
        val nextDueAt = OffsetDateTime.parse("2026-06-10T12:00:00Z")
        val now = OffsetDateTime.parse("2026-06-10T12:03:20Z") // 200s late, interval 60s

        val next = calculator.nextAfterSuccess(
            task(
                ClusterScheduledTaskScheduleKind.FIXED_RATE,
                nextDueAt = nextDueAt,
                intervalMs = 60_000,
                catchUpPolicy = ClusterScheduledTaskCatchUpPolicy.COALESCE,
            ),
            now = now,
        )

        // 12:01, 12:02, 12:03 are all <= now; first slot strictly after now is 12:04.
        assertThat(next).isEqualTo(OffsetDateTime.parse("2026-06-10T12:04:00Z"))
    }

    @Test
    fun `fixed rate catch-up advances exactly one interval even when far behind`() {
        val nextDueAt = OffsetDateTime.parse("2026-06-10T12:00:00Z")
        val now = OffsetDateTime.parse("2026-06-10T12:03:20Z")

        val next = calculator.nextAfterSuccess(
            task(
                ClusterScheduledTaskScheduleKind.FIXED_RATE,
                nextDueAt = nextDueAt,
                intervalMs = 60_000,
                catchUpPolicy = ClusterScheduledTaskCatchUpPolicy.CATCH_UP,
            ),
            now = now,
        )

        assertThat(next).isEqualTo(OffsetDateTime.parse("2026-06-10T12:01:00Z"))
    }

    @Test
    fun `cron catch-up advances one occurrence from the previous due time`() {
        val nextDueAt = OffsetDateTime.parse("2026-06-10T02:00:00Z")
        val now = OffsetDateTime.parse("2026-06-12T15:00:00Z") // missed 06-11

        val next = calculator.nextAfterSuccess(
            task(
                ClusterScheduledTaskScheduleKind.CRON,
                nextDueAt = nextDueAt,
                cronExpression = "0 0 2 * * ?",
                catchUpPolicy = ClusterScheduledTaskCatchUpPolicy.CATCH_UP,
            ),
            now = now,
        )

        assertThat(next).isEqualTo(OffsetDateTime.parse("2026-06-11T02:00:00Z"))
    }

    @Test
    fun `cron coalesce jumps past missed occurrences to the next future run`() {
        val nextDueAt = OffsetDateTime.parse("2026-06-10T02:00:00Z")
        val now = OffsetDateTime.parse("2026-06-12T15:00:00Z")

        val next = calculator.nextAfterSuccess(
            task(
                ClusterScheduledTaskScheduleKind.CRON,
                nextDueAt = nextDueAt,
                cronExpression = "0 0 2 * * ?",
                catchUpPolicy = ClusterScheduledTaskCatchUpPolicy.COALESCE,
            ),
            now = now,
        )

        assertThat(next).isEqualTo(OffsetDateTime.parse("2026-06-13T02:00:00Z"))
    }

    @Test
    fun `cron is evaluated in the task zone and tracks the daylight-saving offset`() {
        // New York is EDT (-04:00) after the 2026-03-08 spring-forward, so a daily
        // noon-local cron resolves to 16:00Z, not 17:00Z (EST).
        val now = OffsetDateTime.parse("2026-03-09T13:00:00-04:00")

        val next = calculator.nextAfterSuccess(
            task(
                ClusterScheduledTaskScheduleKind.CRON,
                nextDueAt = now,
                cronExpression = "0 0 12 * * ?",
                zoneId = "America/New_York",
                catchUpPolicy = ClusterScheduledTaskCatchUpPolicy.COALESCE,
            ),
            now = now,
        )

        assertThat(next.toInstant()).isEqualTo(Instant.parse("2026-03-10T16:00:00Z"))
    }

    @Test
    fun `retry backoff doubles per consecutive failure`() {
        val now = OffsetDateTime.parse("2026-06-10T12:00:00Z")

        assertThat(backoff(consecutiveFailures = 0, now = now)).isEqualTo(now.plus(Duration.ofMillis(1_000)))
        assertThat(backoff(consecutiveFailures = 1, now = now)).isEqualTo(now.plus(Duration.ofMillis(2_000)))
        assertThat(backoff(consecutiveFailures = 3, now = now)).isEqualTo(now.plus(Duration.ofMillis(8_000)))
    }

    @Test
    fun `retry backoff shift saturates at ten doublings`() {
        val now = OffsetDateTime.parse("2026-06-10T12:00:00Z")
        // 2^10 = 1024; with a high ceiling the shift, not the cap, is the limiter.
        val expected = now.plus(Duration.ofMillis(1_000 * 1024))

        assertThat(backoff(consecutiveFailures = 10, now = now, maxRetryDelayMs = 10_000_000))
            .isEqualTo(expected)
        // 11+ failures keep the same delay because the shift is capped at 10.
        assertThat(backoff(consecutiveFailures = 11, now = now, maxRetryDelayMs = 10_000_000))
            .isEqualTo(expected)
    }

    @Test
    fun `retry backoff never exceeds the configured ceiling`() {
        val now = OffsetDateTime.parse("2026-06-10T12:00:00Z")

        val next = backoff(consecutiveFailures = 8, now = now, retryDelayMs = 1_000, maxRetryDelayMs = 5_000)

        // 1_000 * 2^8 = 256_000 would blow past the 5_000 ceiling.
        assertThat(next).isEqualTo(now.plus(Duration.ofMillis(5_000)))
    }

    @Test
    fun `advance-on-failure schedules the next normal occurrence instead of a retry`() {
        val nextDueAt = OffsetDateTime.parse("2026-06-10T12:00:00Z")
        val now = OffsetDateTime.parse("2026-06-10T12:00:01Z")

        val next = calculator.nextAfterFailure(
            task(
                ClusterScheduledTaskScheduleKind.FIXED_RATE,
                nextDueAt = nextDueAt,
                intervalMs = 60_000,
                failurePolicy = ClusterScheduledTaskFailurePolicy.ADVANCE_ON_FAILURE,
            ),
            now = now,
            retryDelayMs = 30_000,
            maxRetryDelayMs = 300_000,
        )

        assertThat(next).isEqualTo(OffsetDateTime.parse("2026-06-10T12:01:00Z"))
    }

    @Test
    fun `invalid cron expression fails fast`() {
        assertThatThrownBy {
            calculator.initialDueAt(definition(ClusterScheduledTaskSchedule.Cron("not a cron")))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown zone id fails fast`() {
        assertThatThrownBy {
            calculator.initialDueAt(
                definition(ClusterScheduledTaskSchedule.Cron("0 0 2 * * ?"), zoneId = "Not/AZone"),
            )
        }.isInstanceOf(java.time.DateTimeException::class.java)
    }

    private fun backoff(
        consecutiveFailures: Int,
        now: OffsetDateTime,
        retryDelayMs: Long = 1_000,
        maxRetryDelayMs: Long = 300_000,
    ): OffsetDateTime = calculator.nextAfterFailure(
        task(
            ClusterScheduledTaskScheduleKind.FIXED_RATE,
            nextDueAt = now,
            intervalMs = 60_000,
            failurePolicy = ClusterScheduledTaskFailurePolicy.RETRY_SAME_DUE,
            consecutiveFailures = consecutiveFailures,
        ),
        now = now,
        retryDelayMs = retryDelayMs,
        maxRetryDelayMs = maxRetryDelayMs,
    )

    private fun definition(
        schedule: ClusterScheduledTaskSchedule,
        zoneId: String = "UTC",
    ): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = "t",
        routingKey = "t",
        taskType = "t",
        schedule = schedule,
        zoneId = zoneId,
    )

    private fun task(
        scheduleKind: ClusterScheduledTaskScheduleKind,
        nextDueAt: OffsetDateTime,
        cronExpression: String? = null,
        intervalMs: Long? = null,
        zoneId: String = "UTC",
        failurePolicy: ClusterScheduledTaskFailurePolicy = ClusterScheduledTaskFailurePolicy.RETRY_SAME_DUE,
        catchUpPolicy: ClusterScheduledTaskCatchUpPolicy = ClusterScheduledTaskCatchUpPolicy.COALESCE,
        consecutiveFailures: Int = 0,
    ): ClusterScheduledTask = ClusterScheduledTask(
        taskKey = "t",
        tenantKey = null,
        routingKey = "t",
        taskType = "t",
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
        requiredCapability = "suite",
        payload = emptyMap(),
        scheduleKind = scheduleKind,
        cronExpression = cronExpression,
        intervalMs = intervalMs,
        zoneId = zoneId,
        failurePolicy = failurePolicy,
        catchUpPolicy = catchUpPolicy,
        enabled = true,
        nextDueAt = nextDueAt,
        leaseOwnerNodeId = null,
        leaseExpiresAt = null,
        lastStartedAt = null,
        lastCompletedAt = null,
        lastFailedAt = null,
        attemptCount = 0,
        consecutiveFailures = consecutiveFailures,
        lastError = null,
        managementMode = "code",
        retiredAt = null,
        retirementReason = null,
        createdAt = nextDueAt,
        updatedAt = nextDueAt,
    )
}

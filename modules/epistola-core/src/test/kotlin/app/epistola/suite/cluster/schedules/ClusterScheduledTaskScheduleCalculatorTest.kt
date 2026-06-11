package app.epistola.suite.cluster.schedules

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
}

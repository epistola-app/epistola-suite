package app.epistola.suite.versioncheck

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class VersionCheckScheduleTest {
    @Test
    fun `daily window cron is stable and lands inside the configured hour`() {
        val installationId = UUID.fromString("00000000-0000-4000-8000-000000000123")
        val properties = VersionCheckProperties(dailyWindowStartHour = 8, dailyWindowMinutes = 60)

        val cron = VersionCheckSchedule.dailyWindowCron(properties, installationId)

        assertThat(cron).isEqualTo(VersionCheckSchedule.dailyWindowCron(properties, installationId))
        val parts = cron.split(" ")
        assertThat(parts[0]).isEqualTo("0")
        assertThat(parts[1].toInt()).isBetween(0, 59)
        assertThat(parts[2]).isEqualTo("8")
        assertThat(cron).endsWith("* * ?")
    }

    @Test
    fun `daily window cron respects narrower spread windows`() {
        val properties = VersionCheckProperties(dailyWindowStartHour = 9, dailyWindowMinutes = 15)

        val minutes = (1..50).map {
            VersionCheckSchedule.dailyWindowCron(properties, UUID.nameUUIDFromBytes("install-$it".toByteArray()))
                .split(" ")[1]
                .toInt()
        }

        assertThat(minutes).allSatisfy { minute -> assertThat(minute).isBetween(0, 14) }
    }

    @Test
    fun `daily window rejects invalid configuration`() {
        assertThatThrownBy {
            VersionCheckSchedule.dailyWindowCron(VersionCheckProperties(dailyWindowStartHour = 24), UUID.randomUUID())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("daily-window-start-hour")

        assertThatThrownBy {
            VersionCheckSchedule.dailyWindowCron(VersionCheckProperties(dailyWindowMinutes = 0), UUID.randomUUID())
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("daily-window-minutes")
    }
}

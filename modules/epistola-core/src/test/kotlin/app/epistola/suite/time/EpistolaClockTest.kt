package app.epistola.suite.time

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class EpistolaClockTest {
    @Test
    fun `withClock binds time for the current scope`() {
        val instant = Instant.parse("2026-06-10T12:34:56Z")

        val scopedInstant = EpistolaClock.withClock(Clock.fixed(instant, ZoneId.of("UTC"))) {
            EpistolaClock.instant()
        }

        assertThat(scopedInstant).isEqualTo(instant)
    }

    @Test
    fun `nested scopes restore the outer clock`() {
        val outer = Instant.parse("2026-06-10T00:00:00Z")
        val inner = Instant.parse("2026-06-11T00:00:00Z")

        val observed = EpistolaClock.withInstant(outer) {
            val insideInner = EpistolaClock.withInstant(inner) {
                EpistolaClock.instant()
            }
            insideInner to EpistolaClock.instant()
        }

        assertThat(observed.first).isEqualTo(inner)
        assertThat(observed.second).isEqualTo(outer)
    }
}

package app.epistola.suite.time

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.MediatorExecutionContext
import app.epistola.suite.mediator.Query
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

    @Test
    fun `current clock falls back to mediator execution context`() {
        val instant = Instant.parse("2026-06-12T00:00:00Z")

        val observed = MediatorContext.runWithContext(
            MediatorExecutionContext(TestMediator, Clock.fixed(instant, ZoneId.of("UTC"))),
        ) {
            EpistolaClock.instant()
        }

        assertThat(observed).isEqualTo(instant)
    }

    @Test
    fun `local clock scope takes precedence over mediator execution context`() {
        val mediatorInstant = Instant.parse("2026-06-12T00:00:00Z")
        val localInstant = Instant.parse("2026-06-13T00:00:00Z")

        val observed = MediatorContext.runWithContext(
            MediatorExecutionContext(TestMediator, Clock.fixed(mediatorInstant, ZoneId.of("UTC"))),
        ) {
            EpistolaClock.withInstant(localInstant) {
                EpistolaClock.instant()
            }
        }

        assertThat(observed).isEqualTo(localInstant)
    }

    private object TestMediator : Mediator {
        override fun <R> send(command: Command<R>): R = error("not used")

        override fun <R> query(query: Query<R>): R = error("not used")
    }
}

package app.epistola.suite.mediator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MediatorContextTest {
    @Test
    fun `runWithMediator binds mediator and captured clock`() {
        val instant = Instant.parse("2026-06-10T10:15:30Z")
        val mediator = TestMediator

        val observed = app.epistola.suite.time.EpistolaClock.withInstant(instant) {
            MediatorContext.runWithMediator(mediator) {
                MediatorContext.current() to MediatorContext.currentClock().instant()
            }
        }

        assertThat(observed.first).isSameAs(mediator)
        assertThat(observed.second).isEqualTo(instant)
    }

    @Test
    fun `runWithContext binds explicit clock`() {
        val instant = Instant.parse("2026-06-11T10:15:30Z")

        val observed = MediatorContext.runWithContext(
            MediatorExecutionContext(TestMediator, Clock.fixed(instant, ZoneId.of("UTC"))),
        ) {
            MediatorContext.currentClock().instant()
        }

        assertThat(observed).isEqualTo(instant)
    }

    private object TestMediator : Mediator {
        override fun <R> send(command: Command<R>): R = error("not used")

        override fun <R> query(query: Query<R>): R = error("not used")
    }
}

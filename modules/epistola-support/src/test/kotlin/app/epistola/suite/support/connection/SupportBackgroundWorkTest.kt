// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.Query
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** One piece of work per key per node, and what was asked while it ran is never lost. */
class SupportBackgroundWorkTest {
    private val mediator = object : Mediator {
        override fun <R> send(command: Command<R>): R = error("not used")

        override fun <R> query(query: Query<R>): R = error("not used")
    }
    private val work = SupportBackgroundWork(mediator)

    @AfterEach
    fun close() = work.close()

    @Test
    fun `a re-run asked for just as the work stops still runs`() {
        val runs = AtomicInteger()
        // Fires once, in the window after the loop decided to stop and before its Future is done:
        // a user's change kicking the round at exactly that moment.
        work.afterLastRound = { key ->
            work.afterLastRound = {}
            work.launch(key, again = true) { runs.incrementAndGet() }
        }

        work.launch("k", again = true) { runs.incrementAndGet() }

        assertThat(work.awaitIdle(Duration.ofSeconds(5))).isTrue()
        assertThat(runs.get()).isEqualTo(2)
    }

    @Test
    fun `a launch while the work runs does not start a second, and with again it runs once more`() {
        val runs = AtomicInteger()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        work.launch("k") {
            runs.incrementAndGet()
            started.countDown()
            release.await()
        }
        // A re-run asked for before the first round begins is covered by that round, so ask only
        // once it really runs.
        assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue()

        assertThat(work.launch("k", again = true) { runs.incrementAndGet() }).isFalse()
        assertThat(work.launch("k", again = true) { runs.incrementAndGet() }).isFalse()
        release.countDown()

        assertThat(work.awaitIdle(Duration.ofSeconds(5))).isTrue()
        assertThat(runs.get()).isEqualTo(2)
    }
}

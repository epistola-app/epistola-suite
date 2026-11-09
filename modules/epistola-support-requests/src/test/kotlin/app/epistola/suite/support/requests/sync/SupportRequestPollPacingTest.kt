// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SupportRequestPollPacingTest {
    private val minute = 60_000L
    private val ms = 1_000_000L

    @Test
    fun `polls every tick while changes come in`() {
        val pacing = SupportRequestPollPacing(minute, 5 * minute)
        assertThat(pacing.isDue(0)).`as`("the first tick").isTrue()

        pacing.record(foundChanges = true, nowNanos = 0)

        assertThat(pacing.isDue(minute * ms)).`as`("the next tick").isTrue()
        assertThat(pacing.currentWaitMs()).isEqualTo(minute)
    }

    @Test
    fun `waits twice as long after each round that found nothing, up to the maximum, and is back at once when changes come`() {
        val pacing = SupportRequestPollPacing(minute, 5 * minute)

        val waits = (1..5).map { round ->
            pacing.record(foundChanges = false, nowNanos = round * 1000 * minute * ms)
            pacing.currentWaitMs()
        }
        assertThat(waits).containsExactly(2 * minute, 4 * minute, 5 * minute, 5 * minute, 5 * minute)

        val end = 10_000 * minute * ms
        pacing.record(foundChanges = false, nowNanos = end)
        assertThat(pacing.isDue(end + minute * ms)).`as`("one tick later").isFalse()
        assertThat(pacing.isDue(end + 4 * minute * ms)).`as`("four ticks later").isFalse()
        assertThat(pacing.isDue(end + 5 * minute * ms)).`as`("five ticks later").isTrue()

        pacing.record(foundChanges = true, nowNanos = end + 5 * minute * ms)
        assertThat(pacing.isDue(end + 6 * minute * ms)).`as`("the tick after changes").isTrue()
    }

    @Test
    fun `a long round does not skip the next tick, as the wait counts from when the round started`() {
        val pacing = SupportRequestPollPacing(intervalMs = 60_000, maxIntervalMs = 300_000)
        val start = 1_000_000_000L
        // A round with many changes that takes 40 seconds.
        pacing.round(start) { true }

        assertThat(pacing.isDue(start + 60_000 * ms)).`as`("the tick a minute after it started").isTrue()
    }

    @Test
    fun `a round that fails backs off like one that found nothing`() {
        val pacing = SupportRequestPollPacing(intervalMs = 60_000, maxIntervalMs = 300_000)

        assertThatThrownBy { pacing.round(0) { error("the service failed") } }.isInstanceOf(IllegalStateException::class.java)

        assertThat(pacing.currentWaitMs()).isEqualTo(120_000)
        assertThat(pacing.isDue(60_000 * ms)).`as`("not polled again at the next tick").isFalse()
    }
}

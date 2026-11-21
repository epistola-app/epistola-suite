// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.common.ids.GenerationRequestKey
import com.github.benmanes.caffeine.cache.Ticker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class PreviewLimitsTest {

    private val form = GenerationRequestKey.generate()

    @Test
    fun `previews of different forms beyond the node's concurrency are refused, and admitted again once one ends`() {
        val limits = PreviewLimits(perMinute = 20, nodeConcurrency = 2)
        val a = GenerationRequestKey.generate()
        val b = GenerationRequestKey.generate()
        val c = GenerationRequestKey.generate()

        // a holds one slot while b renders in the other: c, a third form, finds the node full.
        val inside = limits.withinLimits(a) {
            limits.withinLimits(b) { limits.withinLimits(c) { "c" } ?: "c refused" } ?: "b refused"
        }

        assertThat(inside).isEqualTo("c refused")
        assertThat(limits.withinLimits(c) { "c" }).`as`("after a and b finished").isEqualTo("c")
    }

    @Test
    fun `a form renders one preview at a time`() {
        val limits = PreviewLimits(perMinute = 20, nodeConcurrency = 8)

        val nested = limits.withinLimits(form) { limits.withinLimits(form) { "second" } ?: "second refused" }

        assertThat(nested).isEqualTo("second refused")
    }

    @Test
    fun `a form gets a limited number of previews a minute, and more the next minute`() {
        val now = AtomicLong()
        val limits = PreviewLimits(perMinute = 3, nodeConcurrency = 8, ticker = Ticker { now.get() })

        val outcomes = (1..4).map { limits.withinLimits(form) { "ok" } }
        now.addAndGet(Duration.ofSeconds(61).toNanos())

        assertThat(outcomes).containsExactly("ok", "ok", "ok", null)
        assertThat(limits.withinLimits(form) { "ok" }).isEqualTo("ok")
    }

    @Test
    fun `a failing render frees its slots`() {
        val limits = PreviewLimits(perMinute = 20, nodeConcurrency = 1)

        runCatching { limits.withinLimits(form) { error("render failed") } }

        assertThat(limits.withinLimits(GenerationRequestKey.generate()) { "ok" }).isEqualTo("ok")
    }
}

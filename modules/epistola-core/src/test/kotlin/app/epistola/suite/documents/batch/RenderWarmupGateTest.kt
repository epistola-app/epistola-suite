package app.epistola.suite.documents.batch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The gate holds the JobPoller's concurrent drain back until the render graph is warm
 * (#724). It must default open (so environments with no warmup drain immediately),
 * close while warming, and re-open when done.
 */
class RenderWarmupGateTest {

    @Test
    fun `defaults open so a node with no warmup provider drains immediately`() {
        assertThat(RenderWarmupGate().isReady).isTrue()
    }

    @Test
    fun `closes while warming and reopens when ready`() {
        val gate = RenderWarmupGate()

        gate.beginWarmup()
        assertThat(gate.isReady).isFalse()

        gate.markReady()
        assertThat(gate.isReady).isTrue()
    }
}

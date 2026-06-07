package app.epistola.suite.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ScheduledTaskMetricsTest {

    private val registry = SimpleMeterRegistry()
    private val metrics = ScheduledTaskMetrics(registry)

    @Test
    fun `records a success-tagged sample when the task completes`() {
        metrics.record("demo") { /* no-op */ }

        val timer = registry.find("epistola.scheduled.task.duration")
            .tags("task", "demo", "outcome", "success")
            .timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1L)
    }

    @Test
    fun `records a failure-tagged sample and rethrows when the task throws`() {
        assertThatThrownBy {
            metrics.record("demo") { throw IllegalStateException("boom") }
        }.isInstanceOf(IllegalStateException::class.java)

        val timer = registry.find("epistola.scheduled.task.duration")
            .tags("task", "demo", "outcome", "failure")
            .timer()
        assertThat(timer).isNotNull
        assertThat(timer!!.count()).isEqualTo(1L)
    }
}

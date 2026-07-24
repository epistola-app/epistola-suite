// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.timers.ClusterTimer
import app.epistola.suite.cluster.timers.ClusterTimerHandler
import app.epistola.suite.cluster.timers.ClusterTimerResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ClusterHandlerMappingsTest {
    @Test
    fun `timer handlers must have unique timer types`() {
        assertThatThrownBy {
            uniqueHandlersByType(
                handlerKind = "cluster timer handler",
                handlers = listOf(TestTimerHandler("duplicate"), TestTimerHandler("duplicate")),
            ) { it.timerType }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Duplicate cluster timer handler registered for type(s): duplicate")
    }

    @Test
    fun `scheduled task handlers must have unique task types`() {
        assertThatThrownBy {
            uniqueHandlersByType(
                handlerKind = "cluster scheduled task handler",
                handlers = listOf(TestScheduledTaskHandler("duplicate"), TestScheduledTaskHandler("duplicate")),
            ) { it.taskType }
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Duplicate cluster scheduled task handler registered for type(s): duplicate")
    }

    @Test
    fun `handler map is keyed by handler type`() {
        val handlers = listOf(TestTimerHandler("first"), TestTimerHandler("second"))

        val mapped = uniqueHandlersByType("cluster timer handler", handlers) { it.timerType }

        assertThat(mapped.keys).containsExactlyInAnyOrder("first", "second")
    }

    private class TestTimerHandler(
        override val timerType: String,
    ) : ClusterTimerHandler {
        override fun handle(timer: ClusterTimer): ClusterTimerResult = ClusterTimerResult.Complete
    }

    private class TestScheduledTaskHandler(
        override val taskType: String,
    ) : ClusterScheduledTaskHandler {
        override fun handle(task: ClusterScheduledTask) = Unit
    }
}

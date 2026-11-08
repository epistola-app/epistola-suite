// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.usage

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.support.connection.SupportBackgroundWork
import app.epistola.suite.support.connection.SupportCluster
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Checks twice a minute, on one node per round, whether an hour waits to be reported
 * ([UsageReporter.tick]). Not on a node whose clock is off: its hours would be the wrong ones. Only
 * while the support connection is on.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled"], havingValue = "true")
class UsageReportScheduler(
    private val reporter: UsageReporter,
    private val work: SupportBackgroundWork,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun usageReportTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = "system:$TASK_KEY",
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(INTERVAL_MS),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
        requiredCapability = SupportCluster.CAPABILITY,
    )

    /** Hands the round to [SupportBackgroundWork]: the scheduler thread never waits on the support service. */
    override fun handle(task: ClusterScheduledTask) {
        work.launch(TASK_KEY) { reporter.tick() }
    }

    companion object {
        const val TASK_KEY = "support.usage.report"
        const val TASK_TYPE = "support.usage.report"
        const val INTERVAL_MS = 30_000L
    }
}

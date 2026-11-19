// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.interactive

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.documents.commands.ExpireInteractiveForms
import app.epistola.suite.mediator.Mediator
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Cancels requests whose form expired unsubmitted (contract ADR 0011), every minute, on one node.
 * The form page already treats an expired link as closed on its own, so the sweep's lag only
 * delays the `cancelled` result a caller collects, by at most an interval. Each run drains in
 * batches, so a backlog clears in one run.
 */
@Component
class InteractiveFormExpirySweep(private val mediator: Mediator) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun interactiveFormExpiryScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(INTERVAL_MS),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        var batches = 0
        while (batches++ < MAX_BATCHES_PER_RUN && mediator.send(ExpireInteractiveForms(BATCH)) == BATCH) {
            // A full batch: there may be more due.
        }
    }

    private companion object {
        const val TASK_KEY = "core.interactive-form-expiry"
        const val ROUTING_KEY = "system:core.interactive-form-expiry"
        const val TASK_TYPE = "core.interactive-form-expiry"
        const val INTERVAL_MS = 60_000L
        const val BATCH = 500
        const val MAX_BATCHES_PER_RUN = 20
    }
}

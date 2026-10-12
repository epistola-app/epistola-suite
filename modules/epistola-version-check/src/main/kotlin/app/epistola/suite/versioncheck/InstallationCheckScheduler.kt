// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Runs the installation check once a day, on one node of the cluster, in the version check's daily
 * window but half the window later, so the two calls do not coincide. Never at startup. Absent when
 * the installation id is not shared.
 */
@Component
@ConditionalOnProperty(prefix = "epistola.sharing", name = ["installation-id"], havingValue = "true", matchIfMissing = true)
class InstallationCheckScheduler(
    private val service: InstallationCheckService,
    private val installationService: InstallationService,
    private val properties: VersionCheckProperties,
    private val mediator: Mediator,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun installationCheckScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(
            VersionCheckSchedule.dailyWindowCron(properties, installationService.get().id, offsetMinutes = properties.dailyWindowMinutes / 2),
        ),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        MediatorContext.runWithMediator(mediator) { service.checkNow() }
    }

    companion object {
        const val TASK_KEY = "installation-check.support-status"
        const val ROUTING_KEY = "system:installation-check.support-status"
        const val TASK_TYPE = "installation-check.support-status"
    }
}

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
import java.util.UUID

@Component
@ConditionalOnProperty(
    prefix = "epistola.version-check",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class VersionCheckScheduler(
    private val service: VersionCheckService,
    private val installationService: InstallationService,
    private val properties: VersionCheckProperties,
    private val mediator: Mediator,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun versionCheckScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.Cron(
            VersionCheckSchedule.dailyWindowCron(properties, installationService.get().id),
        ),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        MediatorContext.runWithMediator(mediator) { service.checkNow() }
    }

    companion object {
        const val TASK_KEY = "version-check.latest-release"
        const val ROUTING_KEY = "system:version-check.latest-release"
        const val TASK_TYPE = "version-check.latest-release"
    }
}

object VersionCheckSchedule {
    fun dailyWindowCron(properties: VersionCheckProperties, installationId: UUID): String {
        val startHour = properties.dailyWindowStartHour
        require(startHour in 0..23) { "epistola.version-check.daily-window-start-hour must be between 0 and 23" }

        val windowMinutes = properties.dailyWindowMinutes
        require(windowMinutes in 1..60) { "epistola.version-check.daily-window-minutes must be between 1 and 60" }

        val minute = Math.floorMod(installationId.hashCode(), windowMinutes)
        return "0 $minute $startHour * * ?"
    }
}

package app.epistola.suite.versioncheck

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "epistola.version-check",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class VersionCheckScheduler(
    private val service: VersionCheckService,
    private val properties: VersionCheckProperties,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun versionCheckScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        service.checkNow()
    }

    companion object {
        const val TASK_KEY = "version-check.latest-release"
        const val ROUTING_KEY = "system:version-check.latest-release"
        const val TASK_TYPE = "version-check.latest-release"
    }
}

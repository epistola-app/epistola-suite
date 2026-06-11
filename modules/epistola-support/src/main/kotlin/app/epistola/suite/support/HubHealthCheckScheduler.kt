package app.epistola.suite.support

import app.epistola.suite.cluster.schedules.ClusterScheduledTask
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskDefinition
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskExecutionScope
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskHandler
import app.epistola.suite.cluster.schedules.ClusterScheduledTaskSchedule
import app.epistola.suite.time.EpistolaClock
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Per-node keepalive that keeps this node's hub connectivity fresh during quiet periods. Real hub
 * operations already record connectivity; this only probes when none has happened within
 * [HubHealthCheckProperties.staleAfter]. This is an all-node scheduled task, so every capable node
 * checks itself and a partial outage (one node can't reach the hub) is recorded per node. Active only
 * when the support tier is enabled.
 */
@Component
@EnableConfigurationProperties(HubHealthCheckProperties::class)
@ConditionalOnProperty(
    prefix = "epistola.support",
    name = ["enabled"],
    havingValue = "true",
)
class HubHealthCheckScheduler(
    private val connectivity: HubConnectivityService,
    private val properties: HubHealthCheckProperties,
) : ClusterScheduledTaskHandler {
    override val taskType: String = TASK_TYPE

    @Bean
    fun hubHealthCheckScheduledTaskDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.intervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE,
    )

    override fun handle(task: ClusterScheduledTask) {
        refreshIfStale()
    }

    fun refreshIfStale() {
        val lastContact = connectivity.currentNode().lastCheckedAt
        val stale = lastContact == null || Duration.between(lastContact, EpistolaClock.instant()) >= properties.staleAfter
        if (stale) connectivity.refresh()
    }

    companion object {
        const val TASK_KEY = "support.hub-health-check"
        const val ROUTING_KEY = "system:support.hub-health-check"
        const val TASK_TYPE = "support.hub-health-check"
    }
}

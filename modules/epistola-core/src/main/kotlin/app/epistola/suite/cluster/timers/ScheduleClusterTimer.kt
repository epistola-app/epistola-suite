package app.epistola.suite.cluster.timers

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

data class ScheduleClusterTimer(
    val timerKey: String,
    val routingKey: String,
    val timerType: String,
    val dueAt: OffsetDateTime,
    val requiredCapability: String = ClusterProperties.DEFAULT_CAPABILITY,
    val payload: Map<String, Any?> = emptyMap(),
    val tenantKey: TenantKey? = null,
) : Command<ClusterTimer>,
    SystemInternal

@Component
class ScheduleClusterTimerHandler(
    private val registry: ClusterTimerRegistry,
) : CommandHandler<ScheduleClusterTimer, ClusterTimer> {
    override fun handle(command: ScheduleClusterTimer): ClusterTimer = registry.schedule(
        timerKey = command.timerKey,
        tenantKey = command.tenantKey,
        routingKey = command.routingKey,
        timerType = command.timerType,
        dueAt = command.dueAt,
        requiredCapability = command.requiredCapability,
        payload = command.payload,
    )
}

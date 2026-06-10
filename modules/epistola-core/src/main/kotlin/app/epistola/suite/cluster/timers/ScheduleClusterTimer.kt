package app.epistola.suite.cluster.timers

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Creates or replaces a one-shot cluster timer.
 *
 * Use this command from application code instead of calling
 * [ClusterTimerRegistry] directly. The `timerKey` is an idempotency key:
 * scheduling the same key again replaces the timer's due time, routing
 * metadata, payload, and clears any previous lease/error state.
 */
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

/**
 * Command handler that delegates the durable write to the internal timer
 * persistence boundary.
 */
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

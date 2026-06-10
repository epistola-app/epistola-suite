package app.epistola.suite.cluster

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

data class CancelClusterTimer(
    val timerKey: String,
) : Command<Boolean>,
    SystemInternal

@Component
class CancelClusterTimerHandler(
    private val registry: ClusterTimerRegistry,
) : CommandHandler<CancelClusterTimer, Boolean> {
    override fun handle(command: CancelClusterTimer): Boolean = registry.cancel(command.timerKey)
}

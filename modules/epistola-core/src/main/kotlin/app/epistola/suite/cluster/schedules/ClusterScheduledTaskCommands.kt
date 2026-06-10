package app.epistola.suite.cluster.schedules

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

data class UpsertClusterScheduledTask(
    val definition: ClusterScheduledTaskDefinition,
) : Command<ClusterScheduledTask>,
    SystemInternal

data class EnableClusterScheduledTask(
    val taskKey: String,
    val tenantKey: TenantKey? = null,
) : Command<Boolean>,
    SystemInternal

data class DisableClusterScheduledTask(
    val taskKey: String,
    val tenantKey: TenantKey? = null,
) : Command<Boolean>,
    SystemInternal

data class TriggerClusterScheduledTaskNow(
    val taskKey: String,
    val tenantKey: TenantKey? = null,
) : Command<Boolean>,
    SystemInternal

data object ListClusterScheduledTasks :
    Query<List<ClusterScheduledTask>>,
    SystemInternal

@Component
class UpsertClusterScheduledTaskHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<UpsertClusterScheduledTask, ClusterScheduledTask> {
    override fun handle(command: UpsertClusterScheduledTask): ClusterScheduledTask = registry.upsert(command.definition)
}

@Component
class EnableClusterScheduledTaskHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<EnableClusterScheduledTask, Boolean> {
    override fun handle(command: EnableClusterScheduledTask): Boolean = registry.enable(command.taskKey, command.tenantKey)
}

@Component
class DisableClusterScheduledTaskHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<DisableClusterScheduledTask, Boolean> {
    override fun handle(command: DisableClusterScheduledTask): Boolean = registry.disable(command.taskKey, command.tenantKey)
}

@Component
class TriggerClusterScheduledTaskNowHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<TriggerClusterScheduledTaskNow, Boolean> {
    override fun handle(command: TriggerClusterScheduledTaskNow): Boolean = registry.triggerNow(command.taskKey, command.tenantKey)
}

@Component
class ListClusterScheduledTasksHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : QueryHandler<ListClusterScheduledTasks, List<ClusterScheduledTask>> {
    override fun handle(query: ListClusterScheduledTasks): List<ClusterScheduledTask> = registry.list()
}

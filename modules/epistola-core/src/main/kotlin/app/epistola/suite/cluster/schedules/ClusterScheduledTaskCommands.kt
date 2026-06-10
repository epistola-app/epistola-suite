package app.epistola.suite.cluster.schedules

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.springframework.stereotype.Component

/**
 * Creates or updates a recurring cluster scheduled task definition.
 *
 * This is the mediator-facing write API for code that needs to register or
 * adjust scheduled work. Startup registration also uses the same underlying
 * registry semantics: the task key is stable, and re-upserting the same key
 * updates the durable definition without creating duplicate schedules.
 */
data class UpsertClusterScheduledTask(
    val definition: ClusterScheduledTaskDefinition,
) : Command<ClusterScheduledTask>,
    SystemInternal

/**
 * Enables a scheduled task that was previously disabled.
 *
 * Supplying [tenantKey] makes the operation tenant-scoped, preventing a command
 * from enabling another tenant's task or a system task with the same key.
 */
data class EnableClusterScheduledTask(
    val taskKey: String,
    val tenantKey: TenantKey? = null,
) : Command<Boolean>,
    SystemInternal

/**
 * Disables a scheduled task and releases any local lease metadata.
 *
 * Disabling leaves the durable row in place so it can be re-enabled later and
 * so operational history remains inspectable.
 */
data class DisableClusterScheduledTask(
    val taskKey: String,
    val tenantKey: TenantKey? = null,
) : Command<Boolean>,
    SystemInternal

/**
 * Makes a scheduled task due immediately.
 *
 * This is intended for manual operational retry/trigger actions and tests. It
 * does not execute the handler inline; normal cluster polling and lease
 * claiming still decide which capable node performs the work.
 */
data class TriggerClusterScheduledTaskNow(
    val taskKey: String,
    val tenantKey: TenantKey? = null,
) : Command<Boolean>,
    SystemInternal

/**
 * Lists recurring scheduled task definitions and their current runtime state
 * for operational inspection.
 */
data object ListClusterScheduledTasks :
    Query<List<ClusterScheduledTask>>,
    SystemInternal

/**
 * Command handler that persists scheduled task definitions through the internal
 * scheduled-task registry.
 */
@Component
class UpsertClusterScheduledTaskHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<UpsertClusterScheduledTask, ClusterScheduledTask> {
    override fun handle(command: UpsertClusterScheduledTask): ClusterScheduledTask = registry.upsert(command.definition)
}

/**
 * Command handler for enabling a durable scheduled task definition.
 */
@Component
class EnableClusterScheduledTaskHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<EnableClusterScheduledTask, Boolean> {
    override fun handle(command: EnableClusterScheduledTask): Boolean = registry.enable(command.taskKey, command.tenantKey)
}

/**
 * Command handler for disabling a durable scheduled task definition.
 */
@Component
class DisableClusterScheduledTaskHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<DisableClusterScheduledTask, Boolean> {
    override fun handle(command: DisableClusterScheduledTask): Boolean = registry.disable(command.taskKey, command.tenantKey)
}

/**
 * Command handler for making a scheduled task due on the next scheduler poll.
 */
@Component
class TriggerClusterScheduledTaskNowHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : CommandHandler<TriggerClusterScheduledTaskNow, Boolean> {
    override fun handle(command: TriggerClusterScheduledTaskNow): Boolean = registry.triggerNow(command.taskKey, command.tenantKey)
}

/**
 * Query handler used by Operations and tests to inspect scheduled task state.
 */
@Component
class ListClusterScheduledTasksHandler(
    private val registry: ClusterScheduledTaskRegistry,
) : QueryHandler<ListClusterScheduledTasks, List<ClusterScheduledTask>> {
    override fun handle(query: ListClusterScheduledTasks): List<ClusterScheduledTask> = registry.list()
}

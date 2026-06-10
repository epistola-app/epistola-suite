package app.epistola.suite.cluster.schedules

/**
 * Handler contract for recurring cluster scheduled tasks.
 *
 * Implementations are Spring beans discovered by
 * [ClusterScheduledTaskScheduler]. [taskType] is an explicit dispatch key that
 * must match exactly one registered [ClusterScheduledTaskDefinition]. Handlers
 * should be idempotent because a failed or expired lease may cause the same due
 * occurrence to be retried.
 */
interface ClusterScheduledTaskHandler {
    val taskType: String

    fun handle(task: ClusterScheduledTask)
}

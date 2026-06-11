package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Retires and purges code-defined scheduled tasks whose definition disappeared
 * from code.
 *
 * Detection is registration-based: each node records the schedules it actively
 * registers (see [ClusterScheduledTaskRegistrar]). A code-managed task is
 * orphaned only when no currently-active node vouches for it and its newest
 * registration is older than the configured grace period — naturally safe during
 * rolling deploys, where old nodes keep vouching until they leave.
 *
 * Reconciliation runs both as a native `single_owner` scheduled task (so a fleet
 * that stabilises after a rolling deploy eventually retires orphans even though
 * no node restarts) and is cheap to re-run. The task is itself a code definition,
 * so it always vouches for itself and is never retired.
 */
@Component
class ClusterScheduledTaskReconciler(
    private val nodeRegistry: ClusterNodeRegistry,
    private val taskRegistry: ClusterScheduledTaskRegistry,
    private val properties: ClusterProperties,
) : ClusterScheduledTaskHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    override val taskType: String = TASK_TYPE

    @Bean
    fun scheduledTaskReconciliationDefinition(): ClusterScheduledTaskDefinition = ClusterScheduledTaskDefinition(
        taskKey = TASK_KEY,
        routingKey = ROUTING_KEY,
        taskType = TASK_TYPE,
        schedule = ClusterScheduledTaskSchedule.FixedDelay(properties.scheduledTasks.reconciliationIntervalMs),
        executionScope = ClusterScheduledTaskExecutionScope.SINGLE_OWNER,
    )

    override fun handle(task: ClusterScheduledTask) {
        reconcile()
    }

    /**
     * Soft-retires orphaned code-defined tasks and hard-deletes tasks retired
     * beyond the retention window. Idempotent and safe to call from any node.
     */
    fun reconcile() {
        val now = EpistolaClock.offsetDateTime()
        val activeNodeIds = nodeRegistry.activeNodes().map { it.nodeId }
        val graceBefore = now.minusNanos(properties.scheduledTasks.reconciliationGracePeriodMs * NANOS_PER_MILLI)

        val retirable = taskRegistry.findRetirableCodeTasks(activeNodeIds, graceBefore)
        retirable.forEach { task ->
            if (taskRegistry.retire(task.taskKey, "No code definition present on any active node")) {
                log.info("Retired orphaned cluster scheduled task '{}' (taskType={})", task.taskKey, task.taskType)
            }
        }

        val purgeCutoff = now.minusNanos(properties.scheduledTasks.retiredRetentionMs * NANOS_PER_MILLI)
        val purged = taskRegistry.purgeRetiredBefore(purgeCutoff)
        if (purged > 0) {
            log.info("Purged {} retired cluster scheduled task(s) older than the retention window", purged)
        }
    }

    companion object {
        const val TASK_KEY = "core.scheduled-task-reconciliation"
        const val ROUTING_KEY = "system:core.scheduled-task-reconciliation"
        const val TASK_TYPE = "core.scheduled-task-reconciliation"

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

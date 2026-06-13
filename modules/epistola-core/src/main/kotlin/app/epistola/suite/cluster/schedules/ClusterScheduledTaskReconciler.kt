package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.time.EpistolaClock
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Component

/**
 * Deletes scheduled tasks whose definition disappeared from code.
 *
 * Detection is registration-based: each node records the schedules it carries
 * (see [ClusterScheduledTaskRegistrar]). A task is orphaned only when **no node
 * that carries it has been seen within the grace period** — liveness is judged by
 * the registering node's `cluster_nodes.last_seen_at`, which the heartbeat keeps
 * current. This is naturally safe during rolling deploys (old nodes keep vouching
 * until they leave) and across long-running-node restarts (a restart gap shorter
 * than the grace window still protects). An orphan is deleted outright; a
 * returning definition is simply re-created fresh on the next startup.
 *
 * Runs as a native `single_owner` scheduled task so a fleet that stabilises after
 * a rolling deploy eventually deletes orphans even though no node restarts. It is
 * itself a code definition, so it always vouches for itself and is never deleted.
 */
@Component
class ClusterScheduledTaskReconciler(
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
     * Deletes orphaned scheduled tasks (no live node carries them). Idempotent and
     * safe to call from any node.
     */
    fun reconcile() {
        val seenSince = EpistolaClock.offsetDateTime().minusNanos(properties.scheduledTasks.reconciliationGracePeriodMs * NANOS_PER_MILLI)
        val deleted = taskRegistry.deleteOrphanedTasks(seenSince)
        if (deleted.isNotEmpty()) {
            log.info("Deleted {} orphaned cluster scheduled task(s): {}", deleted.size, deleted)
        }
    }

    companion object {
        const val TASK_KEY = "core.scheduled-task-reconciliation"
        const val ROUTING_KEY = "system:core.scheduled-task-reconciliation"
        const val TASK_TYPE = "core.scheduled-task-reconciliation"

        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

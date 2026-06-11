package app.epistola.suite.cluster.schedules

import app.epistola.suite.cluster.ClusterLeaseRenewer
import app.epistola.suite.cluster.ClusterMaintenanceExecutor
import app.epistola.suite.cluster.ClusterNode
import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
import app.epistola.suite.cluster.timers.ClusterTimerOwnership
import app.epistola.suite.cluster.uniqueHandlersByType
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.observability.recordScheduledTask
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
import app.epistola.suite.time.EpistolaClock
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Polling engine for recurring cluster scheduled tasks.
 *
 * Each [poll] reads due task definitions, filters active nodes by the task's
 * required capability, applies routing-key ownership, and claims only rows
 * owned by the current node. Claiming is still protected by PostgreSQL row
 * leases, so ownership is an affinity mechanism rather than the correctness
 * boundary.
 *
 * This engine owns *what* a poll does, not *when* it runs — the active
 * [app.epistola.suite.cluster.ClusterSchedulingDriver] decides the cadence
 * (wall-clock ticks in production, explicit invocation in tests).
 *
 * Successful handlers advance the recurring definition through
 * [ClusterScheduledTaskScheduleCalculator]. Missing handlers and thrown
 * exceptions are recorded through the registry and scheduled according to the
 * task's failure policy.
 */
@Component
class ClusterScheduledTaskScheduler(
    private val nodeRegistry: ClusterNodeRegistry,
    private val taskRegistry: ClusterScheduledTaskRegistry,
    private val ownership: ClusterTimerOwnership,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val scheduleCalculator: ClusterScheduledTaskScheduleCalculator,
    private val meterRegistry: MeterRegistry,
    private val mediator: Mediator,
    private val maintenanceExecutor: ClusterMaintenanceExecutor,
    handlers: List<ClusterScheduledTaskHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByType = uniqueHandlersByType("cluster scheduled task handler", handlers) { it.taskType }

    @Volatile
    private var shuttingDown = false

    private val leaseRenewer = ClusterLeaseRenewer(
        scheduler = maintenanceExecutor.scheduler,
        renewIntervalMs = properties.scheduledTasks.leaseDurationMs / 3,
    ) { keys -> MediatorContext.runWithMediator(mediator) { taskRegistry.renewLeases(keys) } }

    @PreDestroy
    fun shutdown() {
        shuttingDown = true
        leaseRenewer.close()
    }

    @EventListener(ContextClosedEvent::class)
    fun contextClosing() {
        shuttingDown = true
    }

    /** Runs one poll cycle and returns the number of tasks claimed and dispatched. */
    fun poll(): Int = MediatorContext.runWithMediator(mediator) {
        var dispatched = 0
        meterRegistry.recordScheduledTask("cluster-scheduled-task-poller") {
            if (shuttingDown) return@recordScheduledTask

            nodeRegistry.heartbeat()
            val candidates = taskRegistry.dueCandidates()
            if (candidates.isEmpty()) {
                return@recordScheduledTask
            }

            val activeNodes = activeNodesForTaskOwnership()
            val ownedCandidateKeys = candidates
                .filter { task ->
                    when (task.executionScope) {
                        ClusterScheduledTaskExecutionScope.SINGLE_OWNER -> ownership.isOwnedBy(
                            routingKey = task.routingKey,
                            nodeId = nodeIdentity.nodeId,
                            nodes = activeNodes.withCapability(task.requiredCapability),
                        )

                        ClusterScheduledTaskExecutionScope.EACH_CAPABLE_NODE -> true
                    }
                }
                .map { it.taskKey }

            val claimed = taskRegistry.claimDue(ownedCandidateKeys)
            claimed.forEach { task -> leaseRenewer.withRenewal(task.taskKey) { dispatch(task) } }
            dispatched = claimed.size
        }
        dispatched
    }

    private fun activeNodesForTaskOwnership(): List<ClusterNode> {
        val activeNodes = nodeRegistry.activeNodes()
        if (activeNodes.any { it.nodeId == nodeIdentity.nodeId }) {
            return activeNodes
        }

        val currentNode = nodeRegistry.heartbeat()
        return (activeNodes + currentNode).distinctBy { it.nodeId }.sortedBy { it.nodeId }
    }

    private fun List<ClusterNode>.withCapability(capability: String): List<ClusterNode> = filter { capability in it.capabilities }

    private fun dispatch(task: ClusterScheduledTask) {
        val handler = handlersByType[task.taskType]
        if (handler == null) {
            // A missing handler almost always means the definition was removed
            // from code and the row is awaiting retirement by the reconciler.
            // Quietly release the lease and advance to the next occurrence instead
            // of recording a failure: no error-state spam, and no tight
            // claim→fail→reclaim loop while reconciliation retires the row.
            log.warn(
                "No handler registered for cluster scheduled task type '{}' (orphaned? awaiting retirement)",
                task.taskType,
            )
            taskRegistry.skipNoHandler(task.taskKey, scheduleCalculator.nextAfterSuccess(task))
            return
        }

        try {
            // Per-taskType timing/outcome so a single slow or failing task is
            // visible in the fleet metrics, not hidden inside the poll-cycle timer.
            meterRegistry.recordScheduledTask("cluster-scheduled-task:${task.taskType}") {
                // A tenant-scoped task runs as that tenant's system principal (mediator
                // authorization + log attribution); system-wide tasks run with none.
                // Consistency follow-up (always bind a system principal): see issue #551.
                val principal = task.tenantKey?.let { SystemUser.principalForTenant(it) }
                if (principal != null) {
                    SecurityContext.runWithPrincipal(principal) { handler.handle(task) }
                } else {
                    handler.handle(task)
                }
            }
            val nextDueAt = scheduleCalculator.nextAfterSuccess(task)
            taskRegistry.complete(task.taskKey, nextDueAt)
        } catch (e: Exception) {
            fail(task, e.message ?: e.javaClass.name)
            log.warn("Cluster scheduled task '{}' failed", task.taskKey, e)
        }
    }

    private fun fail(task: ClusterScheduledTask, error: String) {
        val nextDueAt = scheduleCalculator.nextAfterFailure(
            task = task,
            now = EpistolaClock.offsetDateTime(),
            retryDelayMs = properties.scheduledTasks.retryDelayMs,
            maxRetryDelayMs = properties.scheduledTasks.maxRetryDelayMs,
        )
        taskRegistry.fail(task.taskKey, nextDueAt, error)
    }
}

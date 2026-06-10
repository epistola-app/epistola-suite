package app.epistola.suite.cluster

import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

@Component
@EnableScheduling
class ClusterScheduledTaskScheduler(
    private val nodeRegistry: ClusterNodeRegistry,
    private val taskRegistry: ClusterScheduledTaskRegistry,
    private val ownership: ClusterTimerOwnership,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val scheduleCalculator: ClusterScheduledTaskScheduleCalculator,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    handlers: List<ClusterScheduledTaskHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByType = handlers.associateBy { it.taskType }

    @Volatile
    private var shuttingDown = false

    @PreDestroy
    fun shutdown() {
        shuttingDown = true
    }

    @Scheduled(fixedDelayString = "\${epistola.cluster.scheduled-tasks.poll-interval-ms:1000}")
    fun poll() = meterRegistry.recordScheduledTask("cluster-scheduled-task-poller") {
        if (shuttingDown) return@recordScheduledTask

        val candidates = taskRegistry.dueCandidates()
        if (candidates.isEmpty()) {
            return@recordScheduledTask
        }

        val activeNodes = activeNodesForTaskOwnership()
        val ownedCandidateKeys = candidates
            .filter { ownership.isOwnedBy(it.routingKey, nodeIdentity.nodeId, activeNodes) }
            .map { it.taskKey }

        val claimed = taskRegistry.claimDue(ownedCandidateKeys)
        claimed.forEach { task -> dispatch(task) }
    }

    private fun activeNodesForTaskOwnership(): List<ClusterNode> {
        val activeNodes = nodeRegistry.activeNodes()
        if (activeNodes.any { it.nodeId == nodeIdentity.nodeId }) {
            return activeNodes
        }

        val currentNode = nodeRegistry.heartbeat()
        return (activeNodes + currentNode).distinctBy { it.nodeId }.sortedBy { it.nodeId }
    }

    private fun dispatch(task: ClusterScheduledTask) {
        val handler = handlersByType[task.taskType]
        if (handler == null) {
            fail(task, "No handler registered for scheduled task type '${task.taskType}'")
            log.warn("No handler registered for cluster scheduled task type '{}'", task.taskType)
            return
        }

        try {
            handler.handle(task)
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
            now = OffsetDateTime.now(clock),
            retryDelayMs = properties.scheduledTasks.retryDelayMs,
            maxRetryDelayMs = properties.scheduledTasks.maxRetryDelayMs,
        )
        taskRegistry.fail(task.taskKey, nextDueAt, error)
    }
}

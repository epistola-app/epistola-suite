package app.epistola.suite.cluster

import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

@Component
@EnableScheduling
class ClusterTimerScheduler(
    private val nodeRegistry: ClusterNodeRegistry,
    private val timerRegistry: ClusterTimerRegistry,
    private val ownership: ClusterTimerOwnership,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val meterRegistry: MeterRegistry,
    handlers: List<ClusterTimerHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByType = handlers.associateBy { it.timerType }

    @Scheduled(fixedDelayString = "\${epistola.cluster.timers.poll-interval-ms:1000}")
    fun poll() = meterRegistry.recordScheduledTask("cluster-timer-poller") {
        val candidates = timerRegistry.dueCandidates()
        if (candidates.isEmpty()) {
            return@recordScheduledTask
        }

        val activeNodes = activeNodesForTimerOwnership()
        val ownedCandidateKeys = candidates
            .filter { ownership.isOwnedBy(it.routingKey, nodeIdentity.nodeId, activeNodes) }
            .map { it.timerKey }

        val claimed = timerRegistry.claimDue(ownedCandidateKeys)
        claimed.forEach { timer -> dispatch(timer) }
    }

    private fun activeNodesForTimerOwnership(): List<ClusterNode> {
        val activeNodes = nodeRegistry.activeNodes()
        if (activeNodes.any { it.nodeId == nodeIdentity.nodeId }) {
            return activeNodes
        }

        val currentNode = nodeRegistry.heartbeat()
        return (activeNodes + currentNode).distinctBy { it.nodeId }.sortedBy { it.nodeId }
    }

    private fun dispatch(timer: ClusterTimer) {
        val handler = handlersByType[timer.timerType]
        if (handler == null) {
            val retryAt = OffsetDateTime.now().plusNanos(properties.timers.retryDelayMs * NANOS_PER_MILLI)
            timerRegistry.retryAfterFailure(timer.timerKey, retryAt, "No handler registered for timer type '${timer.timerType}'")
            log.warn("No handler registered for cluster timer type '{}'", timer.timerType)
            return
        }

        try {
            when (val result = handler.handle(timer)) {
                ClusterTimerResult.Complete -> timerRegistry.complete(timer.timerKey)
                is ClusterTimerResult.Reschedule -> timerRegistry.reschedule(timer.timerKey, result.nextDueAt, result.payload)
            }
        } catch (e: Exception) {
            val retryAt = OffsetDateTime.now().plusNanos(properties.timers.retryDelayMs * NANOS_PER_MILLI)
            timerRegistry.retryAfterFailure(timer.timerKey, retryAt, e.message ?: e.javaClass.name)
            log.warn("Cluster timer '{}' failed; retry scheduled for {}", timer.timerKey, retryAt, e)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

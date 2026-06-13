package app.epistola.suite.cluster.timers

import app.epistola.suite.cluster.ClusterLeaseRenewer
import app.epistola.suite.cluster.ClusterMaintenanceExecutor
import app.epistola.suite.cluster.ClusterNode
import app.epistola.suite.cluster.ClusterNodeRegistry
import app.epistola.suite.cluster.ClusterProperties
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
import org.springframework.stereotype.Component

/**
 * Polling engine for one-shot cluster timers.
 *
 * Each [poll] reads due timer candidates, filters the active node set by the
 * timer's required capability, applies rendezvous ownership over the routing
 * key, and only then attempts to claim owned rows through
 * [ClusterTimerRegistry]. Claiming uses PostgreSQL row leases, so competing
 * nodes cannot execute the same claimed row at the same time.
 *
 * This engine owns *what* a poll does, not *when* it runs — the active
 * [app.epistola.suite.cluster.ClusterSchedulingDriver] decides the cadence
 * (wall-clock ticks in production, explicit invocation in tests).
 *
 * The engine dispatches handlers inside a [MediatorContext], which binds the
 * mediator and current application clock. Missing handlers
 * and handler failures do not drop timers; they record the error and schedule a retry.
 */
@Component
class ClusterTimerScheduler(
    private val nodeRegistry: ClusterNodeRegistry,
    private val timerRegistry: ClusterTimerRegistry,
    private val ownership: ClusterTimerOwnership,
    private val nodeIdentity: NodeIdentity,
    private val properties: ClusterProperties,
    private val meterRegistry: MeterRegistry,
    private val mediator: Mediator,
    private val maintenanceExecutor: ClusterMaintenanceExecutor,
    handlers: List<ClusterTimerHandler>,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val handlersByType = uniqueHandlersByType("cluster timer handler", handlers) { it.timerType }

    @Volatile
    private var shuttingDown = false

    private val leaseRenewer = ClusterLeaseRenewer(
        scheduler = maintenanceExecutor.scheduler,
        renewIntervalMs = properties.timers.leaseDurationMs / 3,
    ) { keys -> MediatorContext.runWithMediator(mediator) { timerRegistry.renewLeases(keys) } }

    @PreDestroy
    fun shutdown() {
        shuttingDown = true
        leaseRenewer.close()
    }

    /** Runs one poll cycle and returns the number of timers claimed and dispatched. */
    fun poll(): Int = MediatorContext.runWithMediator(mediator) {
        var dispatched = 0
        meterRegistry.recordScheduledTask("cluster-timer-poller") {
            if (shuttingDown) return@recordScheduledTask

            val candidates = timerRegistry.dueCandidates()
            if (candidates.isEmpty()) {
                return@recordScheduledTask
            }

            val activeNodes = activeNodesForTimerOwnership()
            val ownedCandidateKeys = candidates
                .filter { timer ->
                    ownership.isOwnedBy(
                        routingKey = timer.routingKey,
                        nodeId = nodeIdentity.nodeId,
                        nodes = activeNodes.withCapability(timer.requiredCapability),
                    )
                }
                .map { it.timerKey }

            val claimed = timerRegistry.claimDue(ownedCandidateKeys)
            claimed.forEach { timer -> leaseRenewer.withRenewal(timer.timerKey) { dispatch(timer) } }
            dispatched = claimed.size
        }
        dispatched
    }

    private fun activeNodesForTimerOwnership(): List<ClusterNode> {
        val activeNodes = nodeRegistry.activeNodes()
        if (activeNodes.any { it.nodeId == nodeIdentity.nodeId }) {
            return activeNodes
        }

        val currentNode = nodeRegistry.heartbeat()
        return (activeNodes + currentNode).distinctBy { it.nodeId }.sortedBy { it.nodeId }
    }

    private fun List<ClusterNode>.withCapability(capability: String): List<ClusterNode> = filter { capability in it.capabilities }

    private fun dispatch(timer: ClusterTimer) {
        val handler = handlersByType[timer.timerType]
        if (handler == null) {
            val retryAt = EpistolaClock.offsetDateTime().plusNanos(properties.timers.retryDelayMs * NANOS_PER_MILLI)
            timerRegistry.retryAfterFailure(timer.timerKey, retryAt, "No handler registered for timer type '${timer.timerType}'")
            log.warn("No handler registered for cluster timer type '{}'", timer.timerType)
            return
        }

        try {
            // A tenant-scoped timer runs as that tenant's system principal (mediator
            // authorization + log attribution); system-wide timers run with none.
            // Consistency follow-up (always bind a system principal): see issue #551.
            val principal = timer.tenantKey?.let { SystemUser.principalForTenant(it) }
            val result = meterRegistry.recordScheduledTask("cluster-timer:${timer.timerType}") {
                if (principal != null) {
                    SecurityContext.runWithPrincipal(principal) { handler.handle(timer) }
                } else {
                    handler.handle(timer)
                }
            }
            when (result) {
                ClusterTimerResult.Complete -> timerRegistry.complete(timer.timerKey)
                is ClusterTimerResult.Reschedule -> timerRegistry.reschedule(timer.timerKey, result.nextDueAt, result.payload)
            }
        } catch (e: Exception) {
            val retryAt = EpistolaClock.offsetDateTime().plusNanos(properties.timers.retryDelayMs * NANOS_PER_MILLI)
            timerRegistry.retryAfterFailure(timer.timerKey, retryAt, e.message ?: e.javaClass.name)
            log.warn("Cluster timer '{}' failed; retry scheduled for {}", timer.timerKey, retryAt, e)
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

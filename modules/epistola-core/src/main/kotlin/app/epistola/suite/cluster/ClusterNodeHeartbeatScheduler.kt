package app.epistola.suite.cluster

import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Records this process in the cluster node registry.
 *
 * Heartbeats are intentionally cheap and always available. Other cluster
 * primitives derive active/stale node state from the same registry, so there is
 * no separate "clustering disabled" mode for single-node deployments.
 *
 * This is a scheduling *engine*: it owns what a heartbeat does, not when it
 * runs. The active [ClusterSchedulingDriver] decides the cadence — wall-clock
 * ticks in production, explicit invocation in tests.
 */
@Component
class ClusterNodeHeartbeatScheduler(
    private val registry: ClusterNodeRegistry,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun heartbeat() = meterRegistry.recordScheduledTask("cluster-node-heartbeat") {
        val node = registry.heartbeat()
        log.debug("Cluster node heartbeat complete: nodeId={}", node.nodeId)
    }
}

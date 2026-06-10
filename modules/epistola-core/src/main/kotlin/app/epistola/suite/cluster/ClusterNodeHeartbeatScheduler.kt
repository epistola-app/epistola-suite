package app.epistola.suite.cluster

import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Periodically records this process in the cluster node registry.
 *
 * Heartbeats are intentionally cheap and always enabled. Other cluster
 * primitives derive active/stale node state from the same registry, so there is
 * no separate "clustering disabled" mode for single-node deployments.
 */
@Component
@EnableScheduling
class ClusterNodeHeartbeatScheduler(
    private val registry: ClusterNodeRegistry,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${epistola.cluster.heartbeat-interval-ms:2000}")
    fun heartbeat() = meterRegistry.recordScheduledTask("cluster-node-heartbeat") {
        val node = registry.heartbeat()
        log.debug("Cluster node heartbeat complete: nodeId={}", node.nodeId)
    }
}

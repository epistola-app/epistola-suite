package app.epistola.suite.cluster

import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Periodically records this process in the cluster node registry.
 *
 * Heartbeats are intentionally cheap and always enabled. Other cluster
 * primitives derive active/stale node state from the same registry, so there is
 * no separate "clustering disabled" mode for single-node deployments.
 *
 * Liveness must never be coupled to work. The cluster pollers run scheduled-task
 * and timer handlers on the shared Spring `@Scheduled` thread pool (default size
 * 1); a single slow handler would otherwise starve a `@Scheduled` heartbeat,
 * making a perfectly healthy node look stale, shifting routing-key ownership, and
 * — once its lease expires — letting another node re-run the in-flight occurrence.
 * Running the heartbeat on its own dedicated single-thread executor decouples it
 * from all handler work, so the registry view stays accurate no matter how long a
 * handler blocks.
 */
@Component
class ClusterNodeHeartbeatScheduler(
    private val registry: ClusterNodeRegistry,
    private val properties: ClusterProperties,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var shuttingDown = false

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cluster-node-heartbeat").apply { isDaemon = true }
    }

    @PostConstruct
    fun start() {
        val intervalMs = properties.heartbeatIntervalMs.coerceAtLeast(1)
        executor.scheduleWithFixedDelay(::runHeartbeatSafely, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Records one heartbeat, emitting the `success`/`failure` outcome metric.
     * Re-throws so callers (and the metric) see failures.
     */
    fun heartbeat() = meterRegistry.recordScheduledTask("cluster-node-heartbeat") {
        val node = registry.heartbeat()
        log.debug("Cluster node heartbeat complete: nodeId={}", node.nodeId)
    }

    /**
     * Heartbeat wrapper for the dedicated executor. A `scheduleWithFixedDelay`
     * task that throws is silently cancelled forever, so a transient database
     * blip must never be allowed to stop the heartbeat: swallow after recording.
     */
    private fun runHeartbeatSafely() {
        try {
            heartbeat()
        } catch (e: Exception) {
            if (shuttingDown) {
                log.debug("Cluster node heartbeat interrupted during shutdown")
            } else {
                log.warn("Cluster node heartbeat failed: {}", e.message, e)
            }
        }
    }

    @PreDestroy
    fun stop() {
        shuttingDown = true
        executor.shutdownNow()
    }
}

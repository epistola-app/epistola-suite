// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import app.epistola.suite.observability.recordScheduledTask
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Records this process in the cluster node registry.
 *
 * Heartbeats are intentionally cheap and always available. Other cluster
 * primitives derive active/stale node state from the same registry, so there is
 * no separate "clustering disabled" mode for single-node deployments.
 *
 * Liveness must never be coupled to work. The cluster pollers run scheduled-task
 * and timer handlers on the shared Spring `@Scheduled` thread pool (the
 * [app.epistola.suite.cluster.ClusterSchedulingDriver] ticks the pollers there);
 * a single slow handler would otherwise starve a `@Scheduled` heartbeat, making a
 * perfectly healthy node look stale, shifting routing-key ownership, and — once
 * its lease expires — letting another node re-run the in-flight occurrence.
 * Running the heartbeat on the shared [ClusterMaintenanceExecutor] thread (which
 * never runs handler work) decouples it from all handler work, so the registry
 * view stays accurate no matter how long a handler blocks. Unlike the pollers,
 * the heartbeat is therefore not routed through the scheduling driver; the
 * deterministic test substrate invokes [heartbeat] directly when it runs due work.
 */
@Component
class ClusterNodeHeartbeatScheduler(
    private val registry: ClusterNodeRegistry,
    private val properties: ClusterProperties,
    private val meterRegistry: MeterRegistry,
    private val maintenanceExecutor: ClusterMaintenanceExecutor,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failureLog = HeartbeatFailureLog(log)

    @Volatile
    private var shuttingDown = false
    private var task: ScheduledFuture<*>? = null

    @PostConstruct
    fun start() {
        // Only self-schedule under the wall-clock substrate. The deterministic test
        // substrate drives the heartbeat explicitly (poll()/runDue()) inside the bound
        // test clock; an autonomous heartbeat there runs on this thread with the system
        // clock and would write a `last_seen_at` that undercuts a test which advanced its
        // clock forward — racing the active-node check in claimDue. See ClusterProperties.
        if (!properties.autonomousSchedulingEnabled()) return
        val intervalMs = properties.heartbeatIntervalMs.coerceAtLeast(1)
        task = maintenanceExecutor.scheduler.scheduleWithFixedDelay(::runHeartbeatSafely, 0, intervalMs, TimeUnit.MILLISECONDS)
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
     * Heartbeat wrapper for the maintenance thread. A `scheduleWithFixedDelay`
     * task that throws is silently cancelled forever, so a transient database
     * blip must never be allowed to stop the heartbeat: swallow after recording.
     *
     * Logging is deduplicated via [HeartbeatFailureLog] so a sustained outage
     * (e.g. the database is unreachable for a while) does not flood the log with
     * a full-stack WARN on every interval.
     */
    private fun runHeartbeatSafely() {
        try {
            heartbeat()
            failureLog.recordSuccess()
        } catch (e: Exception) {
            failureLog.recordFailure(e, shuttingDown)
        }
    }

    @PreDestroy
    fun stop() {
        shuttingDown = true
        task?.cancel(false)
    }
}

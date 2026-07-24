// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Enables cluster coordination configuration properties.
 *
 * The concrete cluster services live in this package and are discovered as
 * Spring components; this configuration class only makes the typed
 * `epistola.cluster.*` settings available for injection.
 */
@Configuration
@EnableConfigurationProperties(ClusterProperties::class)
class ClusterConfiguration

/**
 * Cluster coordination settings.
 *
 * The runtime is always a cluster, even when the deployment has one node.
 * Phase 1 uses these settings only for the cluster-node heartbeat registry.
 * Later phases reuse the same active-node view for sticky timer events,
 * durable processes, cache invalidation subscriptions, and capability-aware
 * work claiming.
 */
@ConfigurationProperties(prefix = "epistola.cluster")
data class ClusterProperties(
    val heartbeatIntervalMs: Long = 2_000,
    val idleTimeoutMs: Long = 10_000,
    val capabilities: List<String> = listOf(DEFAULT_CAPABILITY),
    val timers: ClusterTimerProperties = ClusterTimerProperties(),
    val scheduledTasks: ClusterScheduledTaskProperties = ClusterScheduledTaskProperties(),
    /**
     * Which substrate triggers autonomous cluster work:
     * [SUBSTRATE_WALL_CLOCK] (production default — pollers tick via the
     * `@Scheduled` [WallClockClusterSchedulingDriver] and the node heartbeat
     * self-schedules on the [ClusterMaintenanceExecutor]) or [SUBSTRATE_TEST]
     * (deterministic driver from `modules/testing`; nothing runs autonomously,
     * pollers and heartbeat are invoked explicitly on the test thread within the
     * bound test clock). Read as a bean/lifecycle condition, not injected for behaviour.
     */
    val schedulingSubstrate: String = SUBSTRATE_WALL_CLOCK,
) {
    /**
     * The capability set this node advertises into `cluster_nodes`.
     *
     * Starts from the configured `epistola.cluster.capabilities` (trimmed, de-duped,
     * defaulting to `[suite]` when blank) and then folds in the PDF-rendering decision:
     * the two render tasks require [PDF_RENDER_CAPABILITY], so a node renders iff it advertises
     * it. [pdfRenderEnabled] — bound from `epistola.generation.pdf-render.enabled` (default true) —
     * **adds** `pdf-render` to the set; setting it false **removes** `pdf-render`, which is how an
     * operator turns off rendering on a node that otherwise carries `suite`. A dedicated worker
     * (`apps/pdfrender`) instead sets `epistola.cluster.capabilities: [pdf-render]` explicitly and
     * leaves the flag at its default.
     */
    fun normalizedCapabilities(pdfRenderEnabled: Boolean = true): List<String> {
        val configured = capabilities
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .ifEmpty { listOf(DEFAULT_CAPABILITY) }
        return if (pdfRenderEnabled) {
            (configured + PDF_RENDER_CAPABILITY).distinct()
        } else {
            configured.filterNot { it == PDF_RENDER_CAPABILITY }
        }
    }

    /** True when autonomous wall-clock triggering is active (production). */
    fun autonomousSchedulingEnabled(): Boolean = schedulingSubstrate == SUBSTRATE_WALL_CLOCK

    companion object {
        const val DEFAULT_CAPABILITY = "suite"

        /**
         * Capability required to run the document render pipeline (`JobPoller`,
         * `StaleJobRecovery`). Kept separate from [DEFAULT_CAPABILITY] so rendering can be
         * routed to dedicated render workers (`apps/pdfrender`, advertising only this) while
         * all control-plane/maintenance tasks stay gated on [DEFAULT_CAPABILITY].
         */
        const val PDF_RENDER_CAPABILITY = "pdf-render"
        const val SUBSTRATE_WALL_CLOCK = "wall-clock"
        const val SUBSTRATE_TEST = "test"
    }
}

/**
 * Polling and lease settings for one-shot cluster timers.
 *
 * `candidateScanSize` controls how many due rows a node inspects before
 * applying ownership, while `batchSize` controls how many owned rows it attempts
 * to claim in one transaction.
 */
data class ClusterTimerProperties(
    val pollIntervalMs: Long = 1_000,
    val leaseDurationMs: Long = 30_000,
    val retryDelayMs: Long = 30_000,
    val batchSize: Int = 25,
    val candidateScanSize: Int = 250,
)

/**
 * Polling, lease, and retry settings for recurring scheduled tasks.
 *
 * Scheduled tasks usually run at lower volume than one-shot timers, so their
 * default scan and claim batches are smaller. Retry delay is capped by
 * `maxRetryDelayMs` when repeated failures back off exponentially.
 */
data class ClusterScheduledTaskProperties(
    val pollIntervalMs: Long = 1_000,
    val leaseDurationMs: Long = 30_000,
    val retryDelayMs: Long = 30_000,
    val maxRetryDelayMs: Long = 300_000,
    val batchSize: Int = 10,
    val candidateScanSize: Int = 100,
    /**
     * How recently a node must have **completed a scheduler poll cycle** to remain
     * eligible as a single-owner task owner. Unlike `idleTimeoutMs` (which keys off
     * the heartbeat, maintained on a separate thread), this keys off the poll thread
     * itself, so a node that is heartbeating but whose scheduler is wedged (see
     * issue #723) drops out of ownership election and its due tasks are re-owned by a
     * healthy node. Comfortably above `pollIntervalMs` so a single slightly-slow
     * cycle does not cause ownership to flap; kept short so recovery is quick. The
     * lease remains the correctness boundary — a still-leased task is never taken
     * over regardless of ownership — so this only accelerates recovery of due tasks
     * that were never claimed. Default 30 seconds.
     */
    val schedulerIdleTimeoutMs: Long = 30_000,
    /**
     * Hard ceiling on how long a single-owner task may stay in-flight before it is
     * **force-reclaimed regardless of its lease**. Normally the lease is the
     * correctness boundary: a running task is never taken over. But a node wedged
     * *mid-dispatch* keeps renewing its lease from the maintenance thread forever
     * (issue #723), so lease expiry never fires and the task would be pinned to the
     * broken node until its process dies. Once a run exceeds this deadline any
     * capable node may reclaim it (via `FOR UPDATE SKIP LOCKED`, so only one does),
     * guaranteeing every recurring task eventually runs again. The cost is a re-run of
     * a task that legitimately exceeds the deadline — and note the failure mode of
     * setting this **too low** is not a single double-run but *accumulating concurrent
     * runs*: a handler that consistently overruns is reclaimed again each deadline
     * while prior runs are still executing. Must therefore be set **well above** the
     * longest expected single-owner handler runtime. Default 15 minutes.
     */
    val maxRunDurationMs: Long = 900_000,
    /**
     * How often the `core.scheduled-task-reconciliation` task deletes orphaned
     * code-defined tasks (no live node carries them). Default hourly.
     */
    val reconciliationIntervalMs: Long = 3_600_000,
    /**
     * A code-defined task is only deleted once **no node that carries it has been
     * seen for this long** — i.e. every carrying node's `cluster_nodes.last_seen_at`
     * is older than this. Judging liveness by the heartbeat-maintained
     * `last_seen_at` means a node restart shorter than this window keeps the node's
     * schedules protected. Default 15 minutes.
     */
    val reconciliationGracePeriodMs: Long = 900_000,
)

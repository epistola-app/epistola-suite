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
    fun normalizedCapabilities(): List<String> = capabilities
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { listOf(DEFAULT_CAPABILITY) }

    /** True when autonomous wall-clock triggering is active (production). */
    fun autonomousSchedulingEnabled(): Boolean = schedulingSubstrate == SUBSTRATE_WALL_CLOCK

    companion object {
        const val DEFAULT_CAPABILITY = "suite"
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

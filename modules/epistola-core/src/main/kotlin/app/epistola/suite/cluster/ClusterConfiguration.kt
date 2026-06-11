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
     * Which [ClusterSchedulingDriver] ticks the scheduling engines:
     * `wall-clock` (production default, `@Scheduled` fixed delays) or `test`
     * (deterministic driver from `modules/testing`, driven explicitly on the
     * test thread). Read as a bean condition, not injected.
     */
    val schedulingSubstrate: String = "wall-clock",
) {
    fun normalizedCapabilities(): List<String> = capabilities
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .ifEmpty { listOf(DEFAULT_CAPABILITY) }

    companion object {
        const val DEFAULT_CAPABILITY = "suite"
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
)

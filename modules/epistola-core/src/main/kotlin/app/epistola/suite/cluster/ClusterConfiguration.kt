package app.epistola.suite.cluster

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

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

data class ClusterTimerProperties(
    val pollIntervalMs: Long = 1_000,
    val leaseDurationMs: Long = 30_000,
    val retryDelayMs: Long = 30_000,
    val batchSize: Int = 25,
    val candidateScanSize: Int = 250,
)

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
 * Phase 1 uses these only for the suite-node heartbeat registry. Later phases
 * reuse the same active-node view for sticky timer events, durable processes,
 * cache invalidation subscriptions, and capability-aware work claiming.
 */
@ConfigurationProperties(prefix = "epistola.cluster")
data class ClusterProperties(
    val enabled: Boolean = true,
    val heartbeatIntervalMs: Long = 10_000,
    val idleTimeoutMs: Long = 30_000,
    val capabilities: List<String> = listOf(DEFAULT_CAPABILITY),
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

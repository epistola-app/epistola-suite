package app.epistola.suite.feedback.sync

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Generic sync configuration properties, provider-agnostic.
 *
 * Controls whether outbound sync and inbound polling are enabled.
 * Provider-specific settings (e.g., PAT, repo) are stored per-tenant in the database.
 */
@ConfigurationProperties(prefix = "epistola.feedback.sync")
data class FeedbackSyncProperties(
    val enabled: Boolean = false,
    val retryIntervalMs: Long = 60_000,
    val polling: PollingProperties = PollingProperties(),
) {
    data class PollingProperties(
        val enabled: Boolean = false,
        val intervalMs: Long = 300_000,
    )
}

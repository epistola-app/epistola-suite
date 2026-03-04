package app.epistola.suite.feedback.sync

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Generic sync configuration properties, provider-agnostic.
 *
 * Controls whether outbound sync, inbound polling, and webhook reception are enabled.
 * Provider-specific settings (e.g., GitHub App ID) remain under their own prefixes.
 */
@ConfigurationProperties(prefix = "epistola.feedback.sync")
data class FeedbackSyncProperties(
    val enabled: Boolean = false,
    val retryIntervalMs: Long = 60_000,
    val polling: PollingProperties = PollingProperties(),
    val webhooks: WebhookProperties = WebhookProperties(),
) {
    data class PollingProperties(
        val enabled: Boolean = false,
        val intervalMs: Long = 300_000,
    )

    data class WebhookProperties(
        val enabled: Boolean = false,
    )
}

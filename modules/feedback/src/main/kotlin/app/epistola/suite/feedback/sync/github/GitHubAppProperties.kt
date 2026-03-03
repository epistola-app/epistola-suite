package app.epistola.suite.feedback.sync.github

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for GitHub App integration.
 *
 * Requires a GitHub App with the following permissions:
 * - Issues: Read & Write
 * - Metadata: Read-only
 *
 * The private key is expected to be a PEM-encoded PKCS#8 RSA key.
 */
@ConfigurationProperties(prefix = "epistola.github")
data class GitHubAppProperties(
    val appId: Long,
    val privateKeyPath: String,
    val webhookSecret: String,
    val sync: SyncProperties = SyncProperties(),
    val webhooks: WebhookProperties = WebhookProperties(),
) {
    data class SyncProperties(
        val enabled: Boolean = true,
        val retryIntervalMs: Long = 60_000,
        val maxRetries: Int = 3,
    )

    data class WebhookProperties(
        val enabled: Boolean = false,
    )
}

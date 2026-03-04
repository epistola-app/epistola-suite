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
    val appId: Long? = null,
    val privateKeyPath: String? = null,
    val webhookSecret: String? = null,
    val sync: SyncProperties = SyncProperties(),
    val webhooks: WebhookProperties = WebhookProperties(),
) {

    /** Validated accessors for use when GitHub integration is active. */
    fun requireAppId(): Long = appId ?: error("epistola.github.app-id is required")
    fun requirePrivateKeyPath(): String = privateKeyPath ?: error("epistola.github.private-key-path is required")
    fun requireWebhookSecret(): String = webhookSecret ?: error("epistola.github.webhook-secret is required")
    data class SyncProperties(
        val enabled: Boolean = true,
        val retryIntervalMs: Long = 60_000,
        val maxRetries: Int = 3,
    )

    data class WebhookProperties(
        val enabled: Boolean = false,
    )
}

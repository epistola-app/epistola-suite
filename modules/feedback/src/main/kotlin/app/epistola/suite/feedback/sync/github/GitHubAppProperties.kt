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
 *
 * Only contains GitHub-specific connection settings. Generic sync properties
 * (enabled, retry interval, polling, webhooks) are in [FeedbackSyncProperties].
 */
@ConfigurationProperties(prefix = "epistola.github")
data class GitHubAppProperties(
    val appId: Long? = null,
    val privateKeyPath: String? = null,
    val webhookSecret: String? = null,
) {

    /** Validated accessors for use when GitHub integration is active. */
    fun requireAppId(): Long = appId ?: error("epistola.github.app-id is required")
    fun requirePrivateKeyPath(): String = privateKeyPath ?: error("epistola.github.private-key-path is required")
    fun requireWebhookSecret(): String = webhookSecret ?: error("epistola.github.webhook-secret is required")
}

package app.epistola.suite.feedback.sync.github

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for GitHub webhook integration.
 *
 * Only the webhook secret is needed at the server level. Authentication with the GitHub API
 * uses per-tenant Personal Access Tokens stored in [GitHubSyncSettings].
 */
@ConfigurationProperties(prefix = "epistola.github")
data class GitHubAppProperties(
    val webhookSecret: String? = null,
) {
    fun requireWebhookSecret(): String = webhookSecret ?: error("epistola.github.webhook-secret is required")
}

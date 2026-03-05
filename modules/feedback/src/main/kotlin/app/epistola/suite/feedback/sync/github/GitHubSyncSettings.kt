package app.epistola.suite.feedback.sync.github

/**
 * GitHub-specific settings parsed from [FeedbackSyncConfig.settings] JSONB.
 *
 * Authentication uses a fine-grained Personal Access Token (PAT) created by the tenant admin
 * with Issues read/write access to the target repository.
 */
data class GitHubSyncSettings(
    val personalAccessToken: String,
    val repoOwner: String,
    val repoName: String,
    val label: String,
) {
    val repoFullName: String get() = "$repoOwner/$repoName"
}

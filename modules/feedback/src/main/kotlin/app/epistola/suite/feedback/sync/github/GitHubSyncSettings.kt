package app.epistola.suite.feedback.sync.github

/**
 * GitHub-specific settings parsed from [FeedbackSyncConfig.settings] JSONB.
 */
data class GitHubSyncSettings(
    val installationId: Long,
    val repoOwner: String,
    val repoName: String,
    val label: String? = null,
) {
    val repoFullName: String get() = "$repoOwner/$repoName"
}

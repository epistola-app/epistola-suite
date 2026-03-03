package app.epistola.suite.feedback

import app.epistola.suite.common.ids.TenantKey

data class FeedbackConfig(
    val tenantKey: TenantKey,
    val enabled: Boolean,
    val installationId: Long?,
    val repoOwner: String?,
    val repoName: String?,
    val label: String?,
) {
    val isGitHubConfigured: Boolean
        get() = installationId != null && repoOwner != null && repoName != null

    val repoFullName: String?
        get() = if (repoOwner != null && repoName != null) "$repoOwner/$repoName" else null
}

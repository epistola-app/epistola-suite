package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackConfig
import app.epistola.suite.feedback.FeedbackStatus

/**
 * Port for syncing feedback to an external issue tracker.
 *
 * Implementations include GitHubIssueSyncAdapter (production) and NoOpIssueSyncAdapter (default).
 * This abstraction allows swapping backends (e.g., Jira, Linear) without changing business logic.
 */
interface IssueSyncPort {
    fun createIssue(config: FeedbackConfig, feedback: Feedback, screenshot: ByteArray?): SyncResult
    fun addComment(config: FeedbackConfig, externalRef: String, comment: FeedbackComment): ExternalCommentRef
    fun updateStatus(config: FeedbackConfig, externalRef: String, status: FeedbackStatus)
    fun verifyWebhookSignature(payload: ByteArray, signature: String, secret: String): Boolean
}

data class SyncResult(
    val externalRef: String,
    val externalUrl: String,
)

data class ExternalCommentRef(
    val externalCommentId: Long,
)

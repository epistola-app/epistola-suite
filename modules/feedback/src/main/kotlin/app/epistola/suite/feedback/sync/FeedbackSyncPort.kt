package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.FeedbackSyncConfig
import java.time.Instant

/**
 * Port for syncing feedback to an external issue tracker.
 *
 * Implementations include GitHubIssueSyncAdapter (production) and NoOpFeedbackSyncAdapter (default).
 * This abstraction allows swapping backends (e.g., GitHub, Jira, Linear) without changing business logic.
 */
interface FeedbackSyncPort {
    fun createTicket(config: FeedbackSyncConfig, feedback: Feedback, screenshot: ByteArray?): SyncResult
    fun addComment(config: FeedbackSyncConfig, externalRef: String, comment: FeedbackComment): ExternalCommentRef
    fun updateStatus(config: FeedbackSyncConfig, externalRef: String, status: FeedbackStatus)
    fun fetchUpdates(config: FeedbackSyncConfig, since: Instant): List<ExternalUpdate>
}

data class SyncResult(
    val externalRef: String,
    val externalUrl: String,
)

data class ExternalCommentRef(
    val externalCommentId: String,
)

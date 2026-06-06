package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackStatus
import org.slf4j.LoggerFactory

/**
 * No-op implementation used when no external sync target is wired (the support tier is
 * disabled). [isEnabled] returns false so the drivers skip syncing entirely, and feedback
 * stays local. Registered as a fallback bean via [FeedbackSyncFallbackConfiguration].
 */
class NoOpFeedbackSyncAdapter : FeedbackSyncPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun isEnabled(): Boolean = false

    override fun createTicket(feedback: Feedback, assets: List<FeedbackAssetContent>): SyncResult {
        log.debug("No-op sync: createTicket called for feedback {}", feedback.id)
        return SyncResult(externalRef = "noop", externalUrl = "")
    }

    override fun addComment(feedback: Feedback, comment: FeedbackComment): ExternalCommentRef {
        log.debug("No-op sync: addComment called for feedback {}", feedback.id)
        return ExternalCommentRef(externalCommentId = "0")
    }

    override fun updateStatus(feedback: Feedback, status: FeedbackStatus) {
        log.debug("No-op sync: updateStatus called for feedback {}", feedback.id)
    }

    override fun fetchUpdates(afterSeq: Long): ExternalUpdatePage {
        log.debug("No-op sync: fetchUpdates called, returning empty page")
        return ExternalUpdatePage(updates = emptyList(), nextSeq = afterSeq, hasMore = false)
    }
}

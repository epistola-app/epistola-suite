package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.FeedbackSyncConfig
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * No-op implementation used when no external issue sync is configured.
 * Logs sync attempts without performing any external calls.
 *
 * Registered as a fallback bean via [FeedbackSyncFallbackConfiguration].
 */
class NoOpFeedbackSyncAdapter : FeedbackSyncPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun createTicket(config: FeedbackSyncConfig, feedback: Feedback, assets: List<FeedbackAssetContent>): SyncResult {
        log.info("No-op sync: would create ticket for feedback {} (provider: {})", feedback.id, config.providerType)
        return SyncResult(externalRef = "noop", externalUrl = "")
    }

    override fun addComment(
        config: FeedbackSyncConfig,
        externalRef: String,
        comment: FeedbackComment,
    ): ExternalCommentRef {
        log.info("No-op sync: would add comment to ticket {} (provider: {})", externalRef, config.providerType)
        return ExternalCommentRef(externalCommentId = "0")
    }

    override fun updateStatus(config: FeedbackSyncConfig, externalRef: String, status: FeedbackStatus) {
        log.info("No-op sync: would update ticket {} to {} (provider: {})", externalRef, status, config.providerType)
    }

    override fun fetchUpdates(config: FeedbackSyncConfig, since: Instant): List<ExternalUpdate> {
        log.debug("No-op sync: fetchUpdates called, returning empty list")
        return emptyList()
    }
}

package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.FeedbackConfig
import app.epistola.suite.feedback.FeedbackStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.stereotype.Component

/**
 * No-op implementation used when no external issue sync is configured.
 * Logs sync attempts without performing any external calls.
 */
@Component
@ConditionalOnMissingBean(IssueSyncPort::class)
class NoOpIssueSyncAdapter : IssueSyncPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun createIssue(config: FeedbackConfig, feedback: Feedback, screenshot: ByteArray?): SyncResult {
        log.info("No-op sync: would create issue for feedback {} in {}", feedback.id, config.repoFullName)
        return SyncResult(externalRef = "noop", externalUrl = "")
    }

    override fun addComment(config: FeedbackConfig, externalRef: String, comment: FeedbackComment): ExternalCommentRef {
        log.info("No-op sync: would add comment to issue {} in {}", externalRef, config.repoFullName)
        return ExternalCommentRef(externalCommentId = 0)
    }

    override fun updateStatus(config: FeedbackConfig, externalRef: String, status: FeedbackStatus) {
        log.info("No-op sync: would update issue {} to {} in {}", externalRef, status, config.repoFullName)
    }

    override fun verifyWebhookSignature(payload: ByteArray, signature: String, secret: String): Boolean {
        log.warn("No-op sync: webhook signature verification called but no sync backend configured")
        return false
    }
}

package app.epistola.suite.feedback.sync

import app.epistola.suite.feedback.FeedbackStatus
import java.time.Instant

/**
 * Represents an update fetched from an external issue tracker via polling.
 */
sealed class ExternalUpdate {
    abstract val externalRef: String
    abstract val occurredAt: Instant

    data class Comment(
        override val externalRef: String,
        override val occurredAt: Instant,
        val externalCommentId: String,
        val authorName: String,
        val authorEmail: String?,
        val body: String,
    ) : ExternalUpdate()

    data class StatusChange(
        override val externalRef: String,
        override val occurredAt: Instant,
        val newStatus: FeedbackStatus,
    ) : ExternalUpdate()
}

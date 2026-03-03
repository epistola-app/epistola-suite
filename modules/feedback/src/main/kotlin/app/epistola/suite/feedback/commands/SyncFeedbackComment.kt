package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackCommentKey
import app.epistola.suite.common.ids.FeedbackKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.CommentSource
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class SyncFeedbackComment(
    val tenantKey: TenantKey,
    val feedbackId: FeedbackKey,
    val body: String,
    val authorName: String,
    val authorEmail: String?,
    val externalCommentId: Long,
) : Command<FeedbackComment?>

@Component
class SyncFeedbackCommentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SyncFeedbackComment, FeedbackComment?> {
    override fun handle(command: SyncFeedbackComment): FeedbackComment? = jdbi.withHandleUnchecked { handle ->
        // Dedup: check if this external comment was already imported
        val existing = handle.createQuery(
            """
            SELECT COUNT(*) FROM feedback_comments
            WHERE tenant_key = :tenantKey AND external_comment_id = :externalCommentId
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("externalCommentId", command.externalCommentId)
            .mapTo(Long::class.java)
            .one()

        if (existing > 0) return@withHandleUnchecked null

        val commentKey = FeedbackCommentKey.generate()

        handle.createQuery(
            """
            INSERT INTO feedback_comments (
                tenant_key, feedback_id, id, body, author_name, author_email,
                source, external_comment_id, created_at
            )
            VALUES (
                :tenantKey, :feedbackId, :id, :body, :authorName, :authorEmail,
                :source, :externalCommentId, NOW()
            )
            RETURNING *
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("feedbackId", command.feedbackId.value)
            .bind("id", commentKey.value)
            .bind("body", command.body)
            .bind("authorName", command.authorName)
            .bind("authorEmail", command.authorEmail)
            .bind("source", CommentSource.GITHUB.name)
            .bind("externalCommentId", command.externalCommentId)
            .mapTo(FeedbackComment::class.java)
            .one()
    }
}

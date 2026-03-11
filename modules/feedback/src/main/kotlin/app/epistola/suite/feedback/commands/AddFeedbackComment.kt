package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.feedback.CommentSource
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class AddFeedbackComment(
    val id: FeedbackCommentId,
    val body: String,
    val authorName: String,
    val authorEmail: String?,
) : Command<FeedbackComment> {
    init {
        require(body.isNotBlank()) { "Comment body is required" }
    }
}

@Component
class AddFeedbackCommentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<AddFeedbackComment, FeedbackComment> {
    override fun handle(command: AddFeedbackComment): FeedbackComment = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            INSERT INTO feedback_comments (
                tenant_key, feedback_id, id, body, author_name, author_email,
                source, created_at
            )
            VALUES (
                :tenantKey, :feedbackId, :id, :body, :authorName, :authorEmail,
                :source, NOW()
            )
            RETURNING *
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.feedbackKey.value)
            .bind("id", command.id.key.value)
            .bind("body", command.body)
            .bind("authorName", command.authorName)
            .bind("authorEmail", command.authorEmail)
            .bind("source", CommentSource.LOCAL.name)
            .mapTo(FeedbackComment::class.java)
            .one()
    }
}

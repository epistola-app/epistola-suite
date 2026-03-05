package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackCommentId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class UpdateFeedbackCommentExternalRef(
    val id: FeedbackCommentId,
    val externalCommentId: String,
) : Command<Boolean>

@Component
class UpdateFeedbackCommentExternalRefHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateFeedbackCommentExternalRef, Boolean> {
    override fun handle(command: UpdateFeedbackCommentExternalRef): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            UPDATE feedback_comments
            SET external_comment_id = :externalCommentId
            WHERE tenant_key = :tenantKey AND feedback_id = :feedbackId AND id = :id
            """,
        )
            .bind("externalCommentId", command.externalCommentId)
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.feedbackKey.value)
            .bind("id", command.id.key.value)
            .execute()
        rows > 0
    }
}

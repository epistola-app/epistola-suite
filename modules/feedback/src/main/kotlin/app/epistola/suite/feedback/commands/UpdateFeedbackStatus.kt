package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class UpdateFeedbackStatus(
    val id: FeedbackId,
    val status: FeedbackStatus,
) : Command<Boolean>

@Component
class UpdateFeedbackStatusHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateFeedbackStatus, Boolean> {
    override fun handle(command: UpdateFeedbackStatus): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            UPDATE feedback
            SET status = :status, updated_at = NOW()
            WHERE tenant_key = :tenantKey AND id = :id
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .bind("status", command.status.name)
            .execute()
        rows > 0
    }
}

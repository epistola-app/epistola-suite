package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class UpdateFeedbackSyncStatus(
    val id: FeedbackId,
    val syncStatus: SyncStatus,
    val incrementAttempts: Boolean = false,
) : Command<Boolean>,
    SystemInternal

@Component
class UpdateFeedbackSyncStatusHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateFeedbackSyncStatus, Boolean> {
    override fun handle(command: UpdateFeedbackSyncStatus): Boolean = jdbi.withHandleUnchecked { handle ->
        val attemptsClause = if (command.incrementAttempts) ", sync_attempts = sync_attempts + 1" else ""
        val rows = handle.createUpdate(
            """
            UPDATE feedback
            SET sync_status = :syncStatus, updated_at = NOW()$attemptsClause
            WHERE tenant_key = :tenantKey AND id = :id
            """,
        )
            .bind("syncStatus", command.syncStatus.name)
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .execute()
        rows > 0
    }
}

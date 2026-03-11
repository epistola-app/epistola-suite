package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class UpdateFeedbackSyncRef(
    val id: FeedbackId,
    val externalRef: String,
    val externalUrl: String,
) : Command<Boolean>

@Component
class UpdateFeedbackSyncRefHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateFeedbackSyncRef, Boolean> {
    override fun handle(command: UpdateFeedbackSyncRef): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            UPDATE feedback
            SET external_ref = :externalRef,
                external_url = :externalUrl,
                sync_status = :syncStatus,
                updated_at = NOW()
            WHERE tenant_key = :tenantKey AND id = :id
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .bind("externalRef", command.externalRef)
            .bind("externalUrl", command.externalUrl)
            .bind("syncStatus", SyncStatus.SYNCED.name)
            .execute()
        rows > 0
    }
}

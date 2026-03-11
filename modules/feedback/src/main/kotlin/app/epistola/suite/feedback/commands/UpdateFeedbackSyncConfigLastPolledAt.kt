package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.time.Instant

data class UpdateFeedbackSyncConfigLastPolledAt(
    val tenantKey: TenantKey,
    val lastPolledAt: Instant,
) : Command<Boolean>

@Component
class UpdateFeedbackSyncConfigLastPolledAtHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateFeedbackSyncConfigLastPolledAt, Boolean> {
    override fun handle(command: UpdateFeedbackSyncConfigLastPolledAt): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            UPDATE feedback_sync_config
            SET last_polled_at = :lastPolledAt
            WHERE tenant_key = :tenantKey
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("lastPolledAt", command.lastPolledAt)
            .execute()
        rows > 0
    }
}

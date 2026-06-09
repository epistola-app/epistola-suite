package app.epistola.suite.feedback.commands

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.feedback.ConditionalOnSupportFeedbackModule
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Applies a status change received from the external sync target (the hub) to the local
 * record. Distinct from [UpdateFeedbackStatus] on purpose: this command is [SystemInternal]
 * and has **no** event handler, so applying an inbound status never re-triggers
 * [app.epistola.suite.feedback.sync.OnFeedbackStatusChanged] — that is what prevents a
 * status sync loop. Only updates when the value actually changes; returns true if a row
 * was updated.
 */
data class SyncFeedbackStatus(
    val id: FeedbackId,
    val status: FeedbackStatus,
) : Command<Boolean>,
    SystemInternal

@Component
@ConditionalOnSupportFeedbackModule
class SyncFeedbackStatusHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SyncFeedbackStatus, Boolean> {
    override fun handle(command: SyncFeedbackStatus): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            UPDATE feedback
            SET status = :status, updated_at = NOW()
            WHERE tenant_key = :tenantKey AND id = :id AND status <> :status
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .bind("status", command.status.name)
            .execute()
        rows > 0
    }
}

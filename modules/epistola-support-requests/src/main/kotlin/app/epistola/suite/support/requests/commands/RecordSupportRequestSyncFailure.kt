// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.exchangedWithLocked
import app.epistola.suite.support.requests.queries.ListPendingSyncSupportRequest
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Sending feedback failed: not before [nextAttemptAt], and with [countAttempt] one attempt more (a
 * refusal; a server error on this item alone is not counted). It reads [SyncStatus.FAILED] once
 * [ListPendingSyncSupportRequest.MAX_SYNC_ATTEMPTS] are counted.
 *
 * Only while the feedback is still waiting ([SyncStatus.PENDING] or [SyncStatus.FAILED]): a
 * withdrawal that landed while the call was on its way moved it to [SyncStatus.NOT_CONFIGURED],
 * and a failed call must not bring it back to waiting. Returns false when nothing was recorded.
 */
data class RecordSupportRequestSyncFailure(
    val id: SupportRequestId,
    val nextAttemptAt: java.time.OffsetDateTime?,
    val countAttempt: Boolean,
    /** The connection it was sent to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Command<Boolean>,
    SystemInternal

@Component
class RecordSupportRequestSyncFailureHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RecordSupportRequestSyncFailure, Boolean> {
    override fun handle(command: RecordSupportRequestSyncFailure): Boolean = jdbi.withHandleUnchecked { handle ->
        handle.createUpdate(
            """
            UPDATE support_request_deliveries
            SET attempts = attempts + :count,
                state = CASE WHEN attempts + :count >= :max THEN :failed ELSE state END,
                next_attempt_at = :nextAttemptAt
            WHERE tenant_key = :tenantKey AND request_id = :id AND connection_id = ${connectionSql(command.connectionId)}
              AND state IN (:pending, :failed)
            """,
        )
            .bind("count", if (command.countAttempt) 1 else 0)
            .bind("max", ListPendingSyncSupportRequest.MAX_SYNC_ATTEMPTS)
            .bind("pending", SyncStatus.PENDING.name)
            .bind("failed", SyncStatus.FAILED.name)
            .bind("nextAttemptAt", command.nextAttemptAt)
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .execute() > 0
    }
}

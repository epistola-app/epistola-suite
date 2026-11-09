// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class UpdateSupportRequestSyncStatus(
    val id: SupportRequestId,
    val syncStatus: SyncStatus,
    val incrementAttempts: Boolean = false,
    /** Not tried again before this; null clears it. */
    val nextAttemptAt: java.time.OffsetDateTime? = null,
) : Command<Boolean>,
    SystemInternal

@Component
class UpdateSupportRequestSyncStatusHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateSupportRequestSyncStatus, Boolean> {
    override fun handle(command: UpdateSupportRequestSyncStatus): Boolean = jdbi.withHandleUnchecked { handle ->
        // NOT_CONFIGURED: the request no longer goes to the connection.
        if (command.syncStatus == SyncStatus.NOT_CONFIGURED) {
            return@withHandleUnchecked handle.createUpdate(
                "DELETE FROM support_request_deliveries WHERE tenant_key = :tenantKey AND request_id = :id AND connection_id = $REQUESTS_CONNECTION_SQL",
            )
                .bind("tenantKey", command.id.tenantKey)
                .bind("id", command.id.key.value)
                .execute() > 0
        }
        val attemptsClause = if (command.incrementAttempts) ", attempts = support_request_deliveries.attempts + 1" else ""
        val rows = handle.createUpdate(
            """
            INSERT INTO support_request_deliveries (tenant_key, request_id, connection_id, state, next_attempt_at, attempts)
            SELECT f.tenant_key, f.id, $REQUESTS_CONNECTION_SQL, :syncStatus, :nextAttemptAt, :initialAttempts
            FROM support_requests f WHERE f.tenant_key = :tenantKey AND f.id = :id
            ON CONFLICT (tenant_key, request_id, connection_id) DO UPDATE
                SET state = EXCLUDED.state, next_attempt_at = EXCLUDED.next_attempt_at$attemptsClause
            """,
        )
            .bind("initialAttempts", if (command.incrementAttempts) 1 else 0)
            .bind("syncStatus", command.syncStatus.name)
            .bind("nextAttemptAt", command.nextAttemptAt)
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .execute()
        rows > 0
    }
}

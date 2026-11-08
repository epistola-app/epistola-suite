// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Applies a status change received from the external sync target (the hub) to the local
 * record. Distinct from [UpdateSupportRequestStatus] on purpose: this command is [SystemInternal]
 * and has **no** event handler, so applying an inbound status never re-triggers
 * [app.epistola.suite.support.requests.sync.OnSupportRequestStatusChanged] — that is what prevents a
 * status sync loop. Only updates when the value actually changes; returns true if a row
 * was updated.
 */
data class SyncSupportRequestStatus(
    val id: SupportRequestId,
    val status: SupportRequestStatus,
) : Command<Boolean>,
    SystemInternal

@Component
class SyncSupportRequestStatusHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SyncSupportRequestStatus, Boolean> {
    override fun handle(command: SyncSupportRequestStatus): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            UPDATE support_requests
            SET status = :status, updated_at = NOW()
            WHERE tenant_key = :tenantKey AND id = :id AND handler_connection = $REQUESTS_CONNECTION_SQL AND status <> :status
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .bind("status", command.status.name)
            .execute()
        rows > 0
    }
}

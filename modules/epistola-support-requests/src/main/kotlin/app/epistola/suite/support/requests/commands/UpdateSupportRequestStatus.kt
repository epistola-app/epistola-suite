// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.exchangedUnderLockedConsent
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class UpdateSupportRequestStatus(
    val id: SupportRequestId,
    val status: SupportRequestStatus,
) : Command<Boolean>,
    RequiresPermission,
    // Its IMMEDIATE handler sends the status to the support service: no open transaction.
    SelfManagedTransaction {
    override val permission get() = Permission.FEEDBACK_MANAGE
    override val tenantKey get() = id.tenantKey
}

@Component
class UpdateSupportRequestStatusHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateSupportRequestStatus, Boolean> {
    override fun handle(command: UpdateSupportRequestStatus): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            "UPDATE support_requests SET status = :status, updated_at = NOW() WHERE tenant_key = :tenantKey AND id = :id AND status <> :status",
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .bind("status", command.status.name)
            .execute()
        // The status it already has: nothing changed, and nothing goes out again.
        if (rows == 0) return@withHandleUnchecked false
        // A request the connection has: the status goes there too, until it took it, but only a
        // change made while it is exchanged under the consent in force. A change made while sharing
        // is off is never sent, also once consent is given again. A request still on its way is
        // marked by UpdateSupportRequestSyncRef if this changed it.
        handle.createUpdate(
            """
            UPDATE support_request_deliveries d
            SET status_unsent = ${exchangedUnderLockedConsent("f")}, next_attempt_at = NULL, status_attempts = 0
            FROM support_requests f
            WHERE f.tenant_key = d.tenant_key AND f.id = d.request_id
              AND d.tenant_key = :tenantKey AND d.request_id = :id AND d.connection_id = $REQUESTS_CONNECTION_SQL
              AND d.remote_ref IS NOT NULL
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .execute()
        rows > 0
    }
}

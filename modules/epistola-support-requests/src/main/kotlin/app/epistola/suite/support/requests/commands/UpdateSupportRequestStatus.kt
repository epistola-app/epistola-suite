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
import app.epistola.suite.support.requests.consent.markStatusUnsentSql
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * The tenant closes its support request ([SupportRequestStatus.CLOSED]) or reopens it
 * ([SupportRequestStatus.OPEN]), whoever handles it (ADR 0053): every other status is the handler's
 * to set. Reopening applies to a request that is closed or resolved; one still open or being worked
 * on stays as it is. Returns whether the status changed.
 */
data class UpdateSupportRequestStatus(
    val id: SupportRequestId,
    val status: SupportRequestStatus,
) : Command<Boolean>,
    RequiresPermission,
    // Its IMMEDIATE handler sends the status to the support service: no open transaction.
    SelfManagedTransaction {
    override val permission get() = Permission.SUPPORT_REQUEST_HANDLE
    override val tenantKey get() = id.tenantKey

    init {
        validate("status", status in TENANT_STATUSES) { "A tenant closes or reopens a support request; $status is the handler's to set" }
    }

    companion object {
        /** The statuses a tenant sets. */
        val TENANT_STATUSES = setOf(SupportRequestStatus.OPEN, SupportRequestStatus.CLOSED)
    }
}

@Component
class UpdateSupportRequestStatusHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateSupportRequestStatus, Boolean> {
    override fun handle(command: UpdateSupportRequestStatus): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            UPDATE support_requests SET status = :status, updated_at = NOW()
            WHERE tenant_key = :tenantKey AND id = :id AND status <> :status
              AND (:status = :closed OR status IN (:closed, :resolved))
            """,
        )
            .bind("closed", SupportRequestStatus.CLOSED.name)
            .bind("resolved", SupportRequestStatus.RESOLVED.name)
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
        // Every connection the request is delivered to, each as its consent says (ADR 0053 step 3).
        handle.createUpdate(markStatusUnsentSql())
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .execute()
        rows > 0
    }
}

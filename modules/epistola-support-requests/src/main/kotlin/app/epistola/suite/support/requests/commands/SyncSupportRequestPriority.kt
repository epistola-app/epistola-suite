// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Applies the priority the handler set (received from the support connection) to the local
 * record. [SystemInternal] with no event handler, like [SyncSupportRequestStatus], so it never goes
 * back out. The requested priority stays as the submitter set it. True when a row changed.
 */
data class SyncSupportRequestPriority(
    val id: SupportRequestId,
    val priority: SupportRequestPriority,
) : Command<Boolean>,
    SystemInternal

@Component
class SyncSupportRequestPriorityHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SyncSupportRequestPriority, Boolean> {
    override fun handle(command: SyncSupportRequestPriority): Boolean = jdbi.withHandleUnchecked { handle ->
        handle.createUpdate(
            """
            UPDATE support_requests
            SET priority = :priority, updated_at = NOW()
            WHERE tenant_key = :tenantKey AND id = :id AND handler_connection = $REQUESTS_CONNECTION_SQL AND priority <> :priority
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("id", command.id.key.value)
            .bind("priority", command.priority.name)
            .execute() > 0
    }
}

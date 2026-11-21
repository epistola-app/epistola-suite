// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.collect.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.tenants.commands.DeleteTenant
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Clears the form data a deleted tenant's results carry (ADR 0056, "Open question"). Results have no
 * foreign key to their tenant, so collectors can still read them after it is deleted; interactive
 * generation copies the submitted `data` and `editedFields` into them, and that personal data must
 * not outlive the tenant. The rest of each result stays.
 *
 * SystemInternal: dispatched by [ForgetDeletedTenantResultDataOnDelete] inside [DeleteTenant], which
 * its caller was already authorized for.
 */
data class ForgetDeletedTenantResultData(val tenantKey: TenantKey) :
    Command<Int>,
    SystemInternal

@Component
class ForgetDeletedTenantResultDataHandler(private val jdbi: Jdbi) : CommandHandler<ForgetDeletedTenantResultData, Int> {
    override fun handle(command: ForgetDeletedTenantResultData): Int = jdbi.withHandle<Int, Exception> { handle ->
        handle.createUpdate(
            "UPDATE generation_results SET data = NULL, edited_fields = NULL WHERE tenant_key = :tenantKey AND (data IS NOT NULL OR edited_fields IS NOT NULL)",
        )
            .bind("tenantKey", command.tenantKey)
            .execute()
    }
}

/** Runs [ForgetDeletedTenantResultData] in the transaction that deletes the tenant, so both happen or neither. */
@Component
class ForgetDeletedTenantResultDataOnDelete : EventHandler<DeleteTenant> {
    override val phase = EventPhase.IMMEDIATE

    override fun on(event: DeleteTenant, result: Any?) {
        if (result == true) ForgetDeletedTenantResultData(event.id).execute()
    }
}

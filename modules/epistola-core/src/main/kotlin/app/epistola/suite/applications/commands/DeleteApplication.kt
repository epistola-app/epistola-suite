// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.commands

import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Delete an application whose keys are all revoked. Its revoked keys stay, unlinked; its documents
 * stay, seen only by callers that see every document. Its id is never reused, so no later
 * application inherits them (ADR 0054).
 *
 * Returns false when the tenant has no such application; refuses one with a key still in use.
 */
data class DeleteApplication(
    override val tenantKey: TenantKey,
    val id: ApplicationKey,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
}

@Component
class DeleteApplicationHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteApplication, Boolean> {

    override fun handle(command: DeleteApplication): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        // Lock the application first, so a key created concurrently waits on its foreign key
        // check and then fails instead of slipping in between the count and the delete.
        val exists = handle.createQuery("SELECT 1 FROM applications WHERE id = :id AND tenant_key = :tenantId FOR UPDATE")
            .bind("id", command.id)
            .bind("tenantId", command.tenantKey)
            .mapTo<Int>()
            .findOne()
            .isPresent
        if (!exists) return@withHandle false

        val activeKeys = handle.createQuery("SELECT COUNT(*) FROM api_keys WHERE application_id = :id AND revoked_at IS NULL")
            .bind("id", command.id)
            .mapTo<Int>()
            .one()
        if (activeKeys > 0) {
            throw ValidationException("id", "Revoke this application's $activeKeys active key(s) before deleting it")
        }

        handle.createUpdate("DELETE FROM applications WHERE id = :id AND tenant_key = :tenantId")
            .bind("id", command.id)
            .bind("tenantId", command.tenantKey)
            .execute() > 0
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.commands

import app.epistola.suite.apikeys.ApiKeyAuthCache
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Make an application the owner of the tenant's unattributed documents — the requests, documents
 * and results that record no application: those from before applications existed, and those a
 * caller without one (a bearer token) requests (ADR 0054) — or, with [owns] false, stop it being
 * their owner. One application per tenant owns them, so making one the owner takes them from
 * whichever application owned them before. Only a restricted application is affected; one that
 * sees every document sees them anyway.
 *
 * The upgrade makes the application of a tenant's only key the owner; with several keys, nothing
 * tells their documents apart, and an administrator chooses here.
 *
 * Returns false when the tenant has no such application.
 */
data class SetUnattributedDocumentsOwner(
    override val tenantKey: TenantKey,
    val id: ApplicationKey,
    val owns: Boolean,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
}

@Component
class SetUnattributedDocumentsOwnerHandler(
    private val jdbi: Jdbi,
    private val apiKeyAuthCache: ApiKeyAuthCache,
) : CommandHandler<SetUnattributedDocumentsOwner, Boolean> {

    override fun handle(command: SetUnattributedDocumentsOwner): Boolean {
        val updated = jdbi.withHandle<Boolean, Exception> { handle ->
            // Lock the tenant's applications, so two administrators choosing different owners at
            // once queue up instead of one failing on the unique index.
            val exists = handle.createQuery("SELECT id FROM applications WHERE tenant_key = :tenantKey FOR UPDATE")
                .bind("tenantKey", command.tenantKey)
                .mapTo(String::class.java)
                .list()
                .contains(command.id.value.toString())
            if (!exists) return@withHandle false
            if (command.owns) {
                // Clear the previous owner first: the partial unique index allows one per tenant.
                handle.createUpdate(
                    "UPDATE applications SET owns_unattributed_documents = false WHERE tenant_key = :tenantKey AND owns_unattributed_documents AND id <> :id",
                )
                    .bind("id", command.id)
                    .bind("tenantKey", command.tenantKey)
                    .execute()
            }
            handle.createUpdate("UPDATE applications SET owns_unattributed_documents = :owns WHERE id = :id AND tenant_key = :tenantKey")
                .bind("id", command.id)
                .bind("tenantKey", command.tenantKey)
                .bind("owns", command.owns)
                .execute()
            true
        }
        // A cached key lookup carries what its application sees; the change applies at once on this
        // node and within the cache's TTL elsewhere, as for a revoke.
        if (updated) apiKeyAuthCache.invalidateAll()
        return updated
    }
}

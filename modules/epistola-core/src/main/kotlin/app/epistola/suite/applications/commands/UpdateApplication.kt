// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.commands

import app.epistola.suite.apikeys.ApiKeyAuthCache
import app.epistola.suite.applications.MAX_APPLICATION_DESCRIPTION_LENGTH
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Rename an application, or change whether it sees every document of the tenant. Switching that
 * off is how an administrator narrows an application the upgrade created from an existing key
 * (ADR 0054, section 3). Its roles are not edited here; they are fixed at creation, as a key's are.
 *
 * Returns false when the tenant has no such application.
 */
data class UpdateApplication(
    val tenantId: TenantKey,
    val id: ApplicationKey,
    val name: String,
    val description: String?,
    val seesAllDocuments: Boolean,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE
    override val tenantKey get() = tenantId

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.trim().length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("description", (description?.trim()?.length ?: 0) <= MAX_APPLICATION_DESCRIPTION_LENGTH) {
            "Description must be $MAX_APPLICATION_DESCRIPTION_LENGTH characters or less"
        }
    }
}

@Component
class UpdateApplicationHandler(
    private val jdbi: Jdbi,
    private val apiKeyAuthCache: ApiKeyAuthCache,
) : CommandHandler<UpdateApplication, Boolean> {

    override fun handle(command: UpdateApplication): Boolean {
        val updated = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE applications
                   SET name = :name, description = :description, sees_all_documents = :seesAllDocuments
                 WHERE id = :id AND tenant_key = :tenantId
                """,
            )
                .bind("id", command.id)
                .bind("tenantId", command.tenantId)
                .bind("name", command.name.trim())
                .bind("description", command.description?.trim()?.takeIf { it.isNotEmpty() })
                .bind("seesAllDocuments", command.seesAllDocuments)
                .execute() > 0
        }
        // A cached key lookup carries what its application sees; narrowing must apply at once on
        // this node, not after the cache's TTL. Other nodes converge within that TTL, as for a revoke.
        if (updated) apiKeyAuthCache.invalidateAll()
        return updated
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.commands

import app.epistola.suite.apikeys.ApiKeyAuthCache
import app.epistola.suite.applications.MAX_APPLICATION_DESCRIPTION_LENGTH
import app.epistola.suite.applications.tenantRolesFromSqlArray
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Rename an application, change whether it sees every document of the tenant, or change its roles.
 * Switching "sees every document" off is how an administrator narrows an application the upgrade
 * created from an existing key (ADR 0054, section 3).
 *
 * [roles] left out keeps them. Every key of the application acts with the new roles at once. An
 * editor may add only roles they hold in the tenant, the same ceiling as creating an application,
 * and may remove any: removing one only narrows what the application can do.
 *
 * Returns false when the tenant has no such application.
 */
data class UpdateApplication(
    override val tenantKey: TenantKey,
    val id: ApplicationKey,
    val name: String,
    val description: String?,
    val seesAllDocuments: Boolean,
    val roles: Set<TenantRole>? = null,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.trim().length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("description", (description?.trim()?.length ?: 0) <= MAX_APPLICATION_DESCRIPTION_LENGTH) {
            "Description must be $MAX_APPLICATION_DESCRIPTION_LENGTH characters or less"
        }
        validate("roles", roles == null || roles.isNotEmpty()) { "Select at least one role" }
    }
}

@Component
class UpdateApplicationHandler(
    private val jdbi: Jdbi,
    private val apiKeyAuthCache: ApiKeyAuthCache,
) : CommandHandler<UpdateApplication, Boolean> {

    override fun handle(command: UpdateApplication): Boolean {
        val updated = jdbi.withHandle<Boolean, Exception> { handle ->
            val current = handle.createQuery("SELECT roles FROM applications WHERE id = :id AND tenant_key = :tenantId FOR UPDATE")
                .bind("id", command.id)
                .bind("tenantId", command.tenantKey)
                .map { rs, _ -> tenantRolesFromSqlArray(rs.getArray("roles")) }
                .findOne()
                .orElse(null) ?: return@withHandle false
            val roles = command.roles ?: current
            // Roles do not inherit, and editing applications is an administrator's permission, so
            // without this an administrator who does not publish could give an application that role.
            val added = roles - current - SecurityContext.current().rolesFor(command.tenantKey)
            validate("roles", added.isEmpty()) {
                "You can only grant roles you hold in this tenant: ${added.sortedBy { it.ordinal }.joinToString { it.name }}"
            }
            handle.createUpdate(
                """
                UPDATE applications
                   SET name = :name, description = :description, sees_all_documents = :seesAllDocuments,
                       roles = :roles::varchar[]
                 WHERE id = :id AND tenant_key = :tenantId
                """,
            )
                .bind("id", command.id)
                .bind("tenantId", command.tenantKey)
                .bind("name", command.name.trim())
                .bind("description", command.description?.trim()?.takeIf { it.isNotEmpty() })
                .bind("seesAllDocuments", command.seesAllDocuments)
                .bind("roles", roles.sortedBy { it.ordinal }.map { it.name }.toTypedArray())
                .execute() > 0
        }
        // A cached key lookup carries its application's roles and what it sees; a change must apply
        // at once on this node, not after the cache's TTL. Other nodes converge within that TTL, as
        // for a revoke.
        if (updated) apiKeyAuthCache.invalidateAll()
        return updated
    }
}

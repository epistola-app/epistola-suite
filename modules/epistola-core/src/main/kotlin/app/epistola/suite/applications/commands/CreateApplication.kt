// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.applications.commands

import app.epistola.suite.applications.Application
import app.epistola.suite.applications.MAX_APPLICATION_DESCRIPTION_LENGTH
import app.epistola.suite.common.ids.ApplicationKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.time.EpistolaClock
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Register a calling system in a tenant (ADR 0054). Its keys act with [roles]; it sees only the
 * documents it requested unless [seesAllDocuments].
 */
data class CreateApplication(
    override val tenantKey: TenantKey,
    val name: String,
    val description: String? = null,
    val roles: Set<TenantRole>,
    val seesAllDocuments: Boolean = false,
) : Command<Application>,
    RequiresPermission {
    override val permission get() = Permission.CREDENTIALS_MANAGE

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.trim().length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
        validate("description", (description?.trim()?.length ?: 0) <= MAX_APPLICATION_DESCRIPTION_LENGTH) {
            "Description must be $MAX_APPLICATION_DESCRIPTION_LENGTH characters or less"
        }
        validate("roles", roles.isNotEmpty()) { "Select at least one role" }
    }
}

@Component
class CreateApplicationHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateApplication, Application> {

    override fun handle(command: CreateApplication): Application {
        // An application may act only with roles its creator holds in the tenant: roles do not
        // inherit, and creating credentials is an administrator's permission, so without this an
        // administrator who does not publish could create an application that does.
        val ungrantable = command.roles - SecurityContext.current().rolesFor(command.tenantKey)
        validate("roles", ungrantable.isEmpty()) {
            "You can only grant roles you hold in this tenant: ${ungrantable.sortedBy { it.ordinal }.joinToString { it.name }}"
        }

        val application = Application(
            id = ApplicationKey.generate(),
            tenantKey = command.tenantKey,
            name = command.name.trim(),
            description = command.description?.trim()?.takeIf { it.isNotEmpty() },
            roles = command.roles,
            seesAllDocuments = command.seesAllDocuments,
            ownsUnattributedDocuments = false,
            createdAt = EpistolaClock.instant(),
            createdBy = currentUserIdOrNull(),
        )
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO applications (id, tenant_key, name, description, roles, sees_all_documents, created_at, created_by)
                VALUES (:id, :tenantId, :name, :description, :roles::varchar[], :seesAllDocuments, :createdAt, :createdBy)
                """,
            )
                .bind("id", application.id)
                .bind("tenantId", application.tenantKey)
                .bind("name", application.name)
                .bind("description", application.description)
                .bind("roles", application.roles.map { it.name }.toTypedArray())
                .bind("seesAllDocuments", application.seesAllDocuments)
                .bind("createdAt", application.createdAt)
                .bind("createdBy", application.createdBy?.value)
                .execute()
        }
        return application
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.Environment
import app.epistola.suite.environments.EnvironmentOverrideRefusedException
import app.epistola.suite.environments.EnvironmentOverrideStore
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class UpdateEnvironment(
    val id: EnvironmentId,
    val name: String,
    /** Marks it production (see [Environment.production]); null keeps it as it is. */
    val production: Boolean? = null,
) : Command<Environment?>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
    override val tenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class UpdateEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateEnvironment, Environment?> {
    override fun handle(command: UpdateEnvironment): Environment? {
        val auditUser = currentUserIdOrNull()?.value
        return jdbi.inTransaction<Environment?, Exception> { handle ->
            if (command.production == true) {
                // Production uses only published versions: a draft it follows has to go first.
                val drafts = EnvironmentOverrideStore.list(handle, command.id.tenantKey, command.id.key, catalogKey = null).filter { it.draftId != null }
                if (drafts.isNotEmpty()) {
                    throw EnvironmentOverrideRefusedException(
                        "'${command.id.key.value}' uses work in progress (${drafts.joinToString { "${it.catalogKey.value}: ${it.label}" }}): " +
                            "point it at a release, or clear the override, before marking it production.",
                    )
                }
            }
            handle.createQuery(
                """
                UPDATE environments
                SET name = :name, production = COALESCE(:production, production), updated_by = :updatedBy
                WHERE id = :id AND tenant_key = :tenantId
                RETURNING *
                """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("name", command.name)
                .bind("production", command.production)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .mapTo<Environment>()
                .findOne()
                .orElse(null)
        }
    }
}

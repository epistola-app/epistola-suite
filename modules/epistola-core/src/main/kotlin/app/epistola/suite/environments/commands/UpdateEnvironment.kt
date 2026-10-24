// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.environments.commands

import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.DraftDeploymentStore
import app.epistola.suite.environments.Environment
import app.epistola.suite.environments.EnvironmentDeploymentRefusedException
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
    /** The revision the caller read ([Environment.revision]); null: no check, last write wins. */
    val expectedRevision: Long? = null,
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
                // Production serves only releases: a draft it serves has to go first.
                val drafts = DraftDeploymentStore.list(handle, command.id.tenantKey, command.id.key, catalog = null)
                if (drafts.isNotEmpty()) {
                    throw EnvironmentDeploymentRefusedException(
                        "'${command.id.key.value}' serves work in progress (${drafts.joinToString { "${it.catalogKey.value}: ${it.draftLabel}" }}): " +
                            "undeploy it, or release it, before marking it production.",
                    )
                }
            }
            handle.createQuery(
                """
                UPDATE environments
                SET name = :name, production = COALESCE(:production, production), updated_by = :updatedBy
                WHERE id = :id AND tenant_key = :tenantId AND ${Revisions.MATCHES}
                RETURNING *
                """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("name", command.name)
                .bind("production", command.production)
                .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                .bind("expectedRevision", command.expectedRevision)
                .mapTo<Environment>()
                .findOne()
                .orElse(null)
                ?: null.also {
                    val current = handle.createQuery("SELECT revision FROM environments WHERE id = :id AND tenant_key = :tenantId")
                        .bind("id", command.id.key).bind("tenantId", command.id.tenantKey).mapTo(Long::class.java).findOne().orElse(null)
                    Revisions.refuse("Environment '${command.id.key.value}'", command.expectedRevision, current)
                }
        }
    }
}

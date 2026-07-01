package app.epistola.suite.environments.commands

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.Environment
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.executeOrThrowDuplicate
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class CreateEnvironment(
    val id: EnvironmentId,
    val name: String,
) : Command<Environment>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
    override val tenantKey get() = id.tenantKey

    init {
        validate("name", name.isNotBlank()) { "Name is required" }
        validate("name", name.length <= MAX_NAME_LENGTH) { "Name must be $MAX_NAME_LENGTH characters or less" }
    }
}

@Component
class CreateEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CreateEnvironment, Environment> {
    override fun handle(command: CreateEnvironment): Environment {
        val auditUser = currentUserIdOrNull()?.value
        return executeOrThrowDuplicate("environment", command.id.key.value) {
            jdbi.withHandle<Environment, Exception> { handle ->
                handle.createQuery(
                    """
                INSERT INTO environments (id, tenant_key, name, created_at, created_by, updated_by)
                VALUES (:id, :tenantId, :name, NOW(), :createdBy, :updatedBy)
                RETURNING *
                """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("name", command.name)
                    .bind("createdBy", auditUser).bind("updatedBy", auditUser)
                    .mapTo<Environment>()
                    .one()
            }
        }
    }
}

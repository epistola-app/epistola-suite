package app.epistola.suite.environments.commands

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.environments.EnvironmentInUseException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class DeleteEnvironment(
    val id: EnvironmentId,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
    override val tenantKey get() = id.tenantKey
}

@Component
class DeleteEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteEnvironment, Boolean> {
    override fun handle(command: DeleteEnvironment): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        // Check if environment has active deployments
        val activationCount = handle.createQuery(
            """
                SELECT COUNT(*) FROM environment_activations
                WHERE tenant_key = :tenantId AND environment_key = :environmentId
                """,
        )
            .bind("tenantId", command.id.tenantKey)
            .bind("environmentId", command.id.key)
            .mapTo<Long>()
            .one()

        if (activationCount > 0) {
            throw EnvironmentInUseException(command.id.key, activationCount)
        }

        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM environments
                WHERE id = :id AND tenant_key = :tenantId
                """,
        )
            .bind("id", command.id.key)
            .bind("tenantId", command.id.tenantKey)
            .execute()
        rowsAffected > 0
    }
}

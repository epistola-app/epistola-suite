package app.epistola.suite.environments.commands

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.environments.EnvironmentInUseException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class DeleteEnvironment(
    val tenantId: TenantId,
    val id: EnvironmentId,
) : Command<Boolean>

@Component
class DeleteEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteEnvironment, Boolean> {
    override fun handle(command: DeleteEnvironment): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        // Check if environment has active deployments
        val activationCount = handle.createQuery(
            """
                SELECT COUNT(*) FROM environment_activations
                WHERE environment_id = :environmentId
                """,
        )
            .bind("environmentId", command.id)
            .mapTo<Long>()
            .one()

        if (activationCount > 0) {
            throw EnvironmentInUseException(command.id, activationCount)
        }

        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM environments
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .execute()
        rowsAffected > 0
    }
}

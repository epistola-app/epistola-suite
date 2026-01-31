package app.epistola.suite.environments.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

data class DeleteEnvironment(
    val tenantId: UUID,
    val id: UUID,
) : Command<Boolean>

@Component
class DeleteEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteEnvironment, Boolean> {
    override fun handle(command: DeleteEnvironment): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
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

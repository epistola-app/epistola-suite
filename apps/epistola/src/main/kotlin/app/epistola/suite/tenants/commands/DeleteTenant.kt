package app.epistola.suite.tenants.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DeleteTenant(
    val id: Long,
) : Command<Boolean>

@Component
class DeleteTenantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteTenant, Boolean> {
    /**
     * Deletes a tenant by ID.
     * Returns true if a tenant was deleted, false if not found.
     * Note: Due to CASCADE, this will also delete all associated templates.
     */
    override fun handle(command: DeleteTenant): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val deleted = handle.createUpdate(
            """
                DELETE FROM tenants WHERE id = :id
                """,
        )
            .bind("id", command.id)
            .execute()
        deleted > 0
    }
}

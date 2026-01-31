package app.epistola.suite.templates.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

data class DeleteDocumentTemplate(
    val tenantId: UUID,
    val id: UUID,
) : Command<Boolean>

@Component
class DeleteDocumentTemplateHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteDocumentTemplate, Boolean> {
    override fun handle(command: DeleteDocumentTemplate): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM document_templates
                WHERE id = :id AND tenant_id = :tenantId
                """,
        )
            .bind("id", command.id)
            .bind("tenantId", command.tenantId)
            .execute()
        rowsAffected > 0
    }
}

package app.epistola.suite.documents.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Command to delete a generated document.
 *
 * @property tenantId Tenant that owns the document
 * @property documentId The document ID to delete
 */
data class DeleteDocument(
    val tenantId: Long,
    val documentId: Long,
) : Command<Boolean>

@Component
class DeleteDocumentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteDocument, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: DeleteDocument): Boolean {
        logger.info("Deleting document {} for tenant {}", command.documentId, command.tenantId)

        return jdbi.inTransaction<Boolean, Exception> { handle ->
            // Delete document - CASCADE will handle generation item references (SET NULL)
            val deleted = handle.createUpdate(
                """
                DELETE FROM documents
                WHERE id = :documentId
                  AND tenant_id = :tenantId
                """,
            )
                .bind("documentId", command.documentId)
                .bind("tenantId", command.tenantId)
                .execute()

            if (deleted > 0) {
                logger.info("Deleted document {}", command.documentId)
                true
            } else {
                logger.warn("Document {} not found for tenant {}", command.documentId, command.tenantId)
                false
            }
        }
    }
}

package app.epistola.suite.documents.commands

import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
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
    val tenantId: TenantKey,
    val documentId: DocumentKey,
) : Command<Boolean>

@Component
class DeleteDocumentHandler(
    private val jdbi: Jdbi,
    private val contentStore: ContentStore,
) : CommandHandler<DeleteDocument, Boolean> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: DeleteDocument): Boolean {
        logger.info("Deleting document {} for tenant {}", command.documentId, command.tenantId)

        val deleted = jdbi.inTransaction<Boolean, Exception> { handle ->
            // Delete document - CASCADE will handle generation item references (SET NULL)
            val rowsDeleted = handle.createUpdate(
                """
                DELETE FROM documents
                WHERE id = :documentId
                  AND tenant_key = :tenantId
                """,
            )
                .bind("documentId", command.documentId)
                .bind("tenantId", command.tenantId)
                .execute()

            rowsDeleted > 0
        }

        if (deleted) {
            contentStore.delete(ContentKey.document(command.tenantId, command.documentId))
            logger.info("Deleted document {}", command.documentId)
        } else {
            logger.warn("Document {} not found for tenant {}", command.documentId, command.tenantId)
        }

        return deleted
    }
}

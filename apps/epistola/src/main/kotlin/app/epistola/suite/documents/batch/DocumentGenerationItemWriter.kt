package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.Document
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.batch.item.Chunk
import org.springframework.batch.item.ItemWriter

/**
 * Spring Batch ItemWriter for generated documents.
 *
 * Writes generated documents to the database and updates item status.
 * Note: Items that failed processing (null Documents) are skipped as they were
 * already marked as FAILED by the processor.
 *
 * @param jdbi JDBI instance for database access
 */
class DocumentGenerationItemWriter(
    private val jdbi: Jdbi,
) : ItemWriter<Document> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun write(chunk: Chunk<out Document>) {
        if (chunk.isEmpty()) {
            return
        }

        jdbi.useTransaction<Exception> { handle ->
            for (document in chunk.items) {
                try {
                    // 1. Insert document into database
                    val documentId = handle.createQuery(
                        """
                        INSERT INTO documents (
                            tenant_id, template_id, variant_id, version_id,
                            filename, content_type, size_bytes, content,
                            created_at, created_by
                        )
                        VALUES (
                            :tenantId, :templateId, :variantId, :versionId,
                            :filename, :contentType, :sizeBytes, :content,
                            :createdAt, :createdBy
                        )
                        RETURNING id
                        """
                    )
                        .bind("tenantId", document.tenantId)
                        .bind("templateId", document.templateId)
                        .bind("variantId", document.variantId)
                        .bind("versionId", document.versionId)
                        .bind("filename", document.filename)
                        .bind("contentType", document.contentType)
                        .bind("sizeBytes", document.sizeBytes)
                        .bind("content", document.content)
                        .bind("createdAt", document.createdAt)
                        .bind("createdBy", document.createdBy)
                        .mapTo<Long>()
                        .one()

                    logger.debug("Created document {} for tenant {}", documentId, document.tenantId)

                    // 2. Update generation item with document_id and status = COMPLETED
                    // We need to find the item by matching request + template + variant
                    // This is safe because items are processed in-order within a chunk
                    handle.createUpdate(
                        """
                        UPDATE document_generation_items
                        SET status = 'COMPLETED',
                            document_id = :documentId,
                            completed_at = NOW()
                        WHERE id = (
                            SELECT id
                            FROM document_generation_items
                            WHERE status = 'IN_PROGRESS'
                              AND template_id = :templateId
                              AND variant_id = :variantId
                              AND version_id = :versionId
                            LIMIT 1
                        )
                        """
                    )
                        .bind("documentId", documentId)
                        .bind("templateId", document.templateId)
                        .bind("variantId", document.variantId)
                        .bind("versionId", document.versionId)
                        .execute()

                    // 3. Increment completed_count on the request
                    handle.createUpdate(
                        """
                        UPDATE document_generation_requests
                        SET completed_count = completed_count + 1
                        WHERE id = (
                            SELECT request_id
                            FROM document_generation_items
                            WHERE document_id = :documentId
                        )
                        """
                    )
                        .bind("documentId", documentId)
                        .execute()

                } catch (e: Exception) {
                    logger.error("Failed to write document for tenant {}: {}", document.tenantId, e.message, e)
                    throw e // Rollback transaction
                }
            }
        }

        logger.info("Successfully wrote {} documents", chunk.size())
    }
}

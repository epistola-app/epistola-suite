package app.epistola.suite.testing

import app.epistola.suite.common.ids.BatchId
import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.documents.model.DocumentGenerationRequest
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value

/**
 * Fake document generation executor for tests.
 *
 * Extends DocumentGenerationExecutor but overrides execute() to skip real PDF generation.
 * Creates minimal fake PDFs instantly for fast tests.
 *
 * Spring injects real dependencies (for parent constructor), but execute() doesn't use them.
 */
class FakeDocumentGenerationExecutor(
    jdbi: Jdbi,
    generationService: app.epistola.suite.generation.GenerationService,
    mediator: app.epistola.suite.mediator.Mediator,
    objectMapper: tools.jackson.databind.ObjectMapper,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    retentionDays: Int = 7,
    @Value("\${epistola.generation.documents.max-size-mb:50}")
    maxDocumentSizeMb: Long = 50,
) : DocumentGenerationExecutor(
    jdbi = jdbi,
    generationService = generationService, // Injected by Spring but not used in fake execute()
    mediator = mediator, // Injected by Spring but not used in fake execute()
    objectMapper = objectMapper, // Injected by Spring but not used in fake execute()
    retentionDays = retentionDays,
    maxDocumentSizeMb = maxDocumentSizeMb,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val localJdbi = jdbi
    private val localRetentionDays = retentionDays

    /**
     * Minimal valid PDF file (just magic bytes + basic structure).
     * This is a valid PDF that can be opened but contains no content.
     */
    private val fakePdfBytes = byteArrayOf(
        0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34, 0x0A, // %PDF-1.4\n
        0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A, // Binary marker
        0x0A, // \n
        // Minimal xref table and trailer
        0x78, 0x72, 0x65, 0x66, 0x0A, // xref\n
        0x30, 0x20, 0x30, 0x0A, // 0 0\n
        0x74, 0x72, 0x61, 0x69, 0x6C, 0x65, 0x72, 0x0A, // trailer\n
        0x3C, 0x3C, 0x3E, 0x3E, 0x0A, // <<>>\n
        0x25, 0x25, 0x45, 0x4F, 0x46, 0x0A, // %%EOF\n
    )

    override fun execute(request: DocumentGenerationRequest) {
        logger.debug("FAKE execution for request {} (no real PDF generation)", request.id.value)

        try {
            // Generate fake document
            val documentId = DocumentId.generate()
            val filename = request.filename ?: "document-${request.id.value}.pdf"

            localJdbi.useTransaction<Exception> { handle ->
                // 1. Claim completion — skip if the request was cancelled during processing
                val expiresAtInterval = "$localRetentionDays days"
                val updated = handle.createUpdate(
                    """
                    UPDATE document_generation_requests
                    SET status = 'COMPLETED',
                        document_id = :documentId,
                        completed_at = NOW(),
                        expires_at = NOW() + :expiresAt::interval
                    WHERE id = :requestId
                      AND status != 'CANCELLED'
                    """,
                )
                    .bind("requestId", request.id)
                    .bind("documentId", documentId)
                    .bind("expiresAt", expiresAtInterval)
                    .execute()

                if (updated == 0) {
                    logger.debug("Request {} was cancelled during processing, skipping", request.id.value)
                    return@useTransaction
                }

                // 2. Insert fake document (only if not cancelled)
                handle.createUpdate(
                    """
                    INSERT INTO documents (
                        id, tenant_id, template_id, variant_id, version_id,
                        filename, correlation_id, content_type, size_bytes, content,
                        created_at, created_by
                    )
                    VALUES (
                        :id, :tenantId, :templateId, :variantId, :versionId,
                        :filename, :correlationId, 'application/pdf', :sizeBytes, :content,
                        NOW(), NULL
                    )
                    """,
                )
                    .bind("id", documentId)
                    .bind("tenantId", request.tenantId)
                    .bind("templateId", request.templateId)
                    .bind("variantId", request.variantId)
                    .bind("versionId", request.versionId ?: request.environmentId) // Use either
                    .bind("filename", filename)
                    .bind("correlationId", request.correlationId)
                    .bind("sizeBytes", fakePdfBytes.size.toLong())
                    .bind("content", fakePdfBytes)
                    .execute()
            }

            // Check if batch is complete and finalize if so
            request.batchId?.let { batchId ->
                finalizeBatchIfComplete(batchId)
            }

            logger.debug("FAKE document {} created for request {}", documentId.value, request.id.value)
        } catch (e: Exception) {
            logger.error("FAKE execution failed for request {}: {}", request.id.value, e.message, e)
            markRequestFailed(request.id, e.message)
            request.batchId?.let { finalizeBatchIfComplete(it) }
        }
    }

    private fun markRequestFailed(requestId: app.epistola.suite.common.ids.GenerationRequestId, errorMessage: String?) {
        val expiresAtInterval = "$localRetentionDays days"
        localJdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'FAILED',
                    error_message = :errorMessage,
                    completed_at = NOW(),
                    expires_at = NOW() + :expiresAt::interval
                WHERE id = :requestId
                  AND status != 'CANCELLED'
                """,
            )
                .bind("requestId", requestId)
                .bind("errorMessage", errorMessage?.take(1000))
                .bind("expiresAt", expiresAtInterval)
                .execute()
        }
    }

    private data class BatchCounts(
        val completed: Int,
        val failed: Int,
        val pending: Int,
        val inProgress: Int,
    ) {
        val isComplete: Boolean get() = pending == 0 && inProgress == 0
    }

    private fun getBatchCounts(batchId: BatchId): BatchCounts = localJdbi.withHandle<BatchCounts, Exception> { handle ->
        val results = handle.createQuery(
            """
                SELECT
                    status,
                    COUNT(*) as count
                FROM document_generation_requests
                WHERE batch_id = :batchId
                GROUP BY status
                """,
        )
            .bind("batchId", batchId)
            .map { rs, _ -> rs.getString("status") to rs.getInt("count") }
            .list()
            .toMap()

        BatchCounts(
            completed = results["COMPLETED"] ?: 0,
            failed = results["FAILED"] ?: 0,
            pending = results["PENDING"] ?: 0,
            inProgress = results["IN_PROGRESS"] ?: 0,
        )
    }

    private fun finalizeBatchIfComplete(batchId: BatchId) {
        val counts = getBatchCounts(batchId)

        if (counts.isComplete) {
            localJdbi.useHandle<Exception> { handle ->
                handle.createUpdate(
                    """
                    UPDATE document_generation_batches
                    SET
                        final_completed_count = :completed,
                        final_failed_count = :failed,
                        completed_at = NOW()
                    WHERE id = :batchId
                      AND completed_at IS NULL
                    """,
                )
                    .bind("batchId", batchId)
                    .bind("completed", counts.completed)
                    .bind("failed", counts.failed)
                    .execute()
            }
        }
    }
}

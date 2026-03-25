package app.epistola.suite.testing

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import java.io.ByteArrayInputStream

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
    contentStore: ContentStore,
    meterRegistry: io.micrometer.core.instrument.MeterRegistry,
    batchAssemblyService: app.epistola.suite.documents.services.BatchAssemblyService,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    retentionDays: Int = 7,
    @Value("\${epistola.generation.documents.max-size-mb:50}")
    maxDocumentSizeMb: Long = 50,
) : DocumentGenerationExecutor(
    jdbi = jdbi,
    generationService = generationService,
    mediator = mediator,
    objectMapper = objectMapper,
    contentStore = contentStore,
    meterRegistry = meterRegistry,
    batchAssemblyService = batchAssemblyService,
    retentionDays = retentionDays,
    maxDocumentSizeMb = maxDocumentSizeMb,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val localJdbi = jdbi
    private val localContentStore = contentStore
    private val localRetentionDays = retentionDays

    /**
     * Minimal valid PDF file with a root catalog and one blank page.
     * PDFBox requires a valid root object to parse and merge PDFs.
     */
    private val fakePdfBytes = (
        "%PDF-1.4\n" +
            "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
            "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
            "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n" +
            "xref\n0 4\n" +
            "0000000000 65535 f \n" +
            "0000000009 00000 n \n" +
            "0000000058 00000 n \n" +
            "0000000115 00000 n \n" +
            "trailer\n<< /Size 4 /Root 1 0 R >>\n" +
            "startxref\n183\n%%EOF\n"
        ).toByteArray()

    override fun execute(request: DocumentGenerationRequest) {
        logger.debug("FAKE execution for request {} (no real PDF generation)", request.id.value)

        try {
            // Generate fake document
            val documentId = DocumentKey.generate()
            val filename = request.filename ?: "document-${request.id.value}.pdf"

            // Store fake PDF content in ContentStore
            localContentStore.put(
                ContentKey.document(request.tenantKey, documentId),
                ByteArrayInputStream(fakePdfBytes),
                "application/pdf",
                fakePdfBytes.size.toLong(),
            )

            localJdbi.useTransaction<Exception> { handle ->
                // 1. Claim completion — skip if the request was cancelled during processing
                val expiresAtInterval = "$localRetentionDays days"
                val updated = handle.createUpdate(
                    """
                    UPDATE document_generation_requests
                    SET status = 'COMPLETED',
                        document_key = :documentId,
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

                // 2. Insert document metadata (content stored in ContentStore)
                handle.createUpdate(
                    """
                    INSERT INTO documents (
                        id, tenant_key, template_key, variant_key, version_key,
                        filename, correlation_id, content_type, size_bytes,
                        created_at, created_by
                    )
                    VALUES (
                        :id, :tenantId, :templateId, :variantId, :versionId,
                        :filename, :correlationId, 'application/pdf', :sizeBytes,
                        NOW(), NULL
                    )
                    """,
                )
                    .bind("id", documentId)
                    .bind("tenantId", request.tenantKey)
                    .bind("templateId", request.templateKey)
                    .bind("variantId", request.variantKey)
                    .bind("versionId", request.versionKey ?: request.environmentKey) // Use either
                    .bind("filename", filename)
                    .bind("correlationId", request.correlationKey)
                    .bind("sizeBytes", fakePdfBytes.size.toLong())
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

    private fun markRequestFailed(requestId: app.epistola.suite.common.ids.GenerationRequestKey, errorMessage: String?) {
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

    private fun getBatchCounts(batchId: BatchKey): BatchCounts = localJdbi.withHandle<BatchCounts, Exception> { handle ->
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

    private fun finalizeBatchIfComplete(batchId: BatchKey) {
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

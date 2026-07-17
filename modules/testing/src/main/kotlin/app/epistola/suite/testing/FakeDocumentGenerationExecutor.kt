package app.epistola.suite.testing

import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.documents.batch.DocumentGenerationExecutor
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.time.EpistolaClock
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
    contentStore: DocumentContentStore,
    meterRegistry: io.micrometer.core.instrument.MeterRegistry,
    schemaValidator: app.epistola.suite.templates.validation.JsonSchemaValidator,
    fontSnapshotVerifier: app.epistola.suite.fonts.FontSnapshotVerifier,
    fontByteCache: app.epistola.suite.fonts.FontByteCache,
    localeResolver: app.epistola.suite.i18n.TenantLocaleResolver,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    retentionDays: Int = 7,
    @Value("\${epistola.generation.documents.max-size-mb:50}")
    maxDocumentSizeMb: Long = 50,
) : DocumentGenerationExecutor(
    jdbi = jdbi,
    generationService = generationService,
    mediator = mediator,
    objectMapper = objectMapper,
    schemaValidator = schemaValidator,
    contentStore = contentStore,
    meterRegistry = meterRegistry,
    fontSnapshotVerifier = fontSnapshotVerifier,
    fontByteCache = fontByteCache,
    localeResolver = localeResolver,
    retentionDays = retentionDays,
    maxDocumentSizeMb = maxDocumentSizeMb,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val localJdbi = jdbi
    private val localContentStore = contentStore
    private val localRetentionDays = retentionDays
    private val localMediator = mediator

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

        // Mirror production's generation meters through the same shared code path
        // (like emitTerminalResult below) so tests exercise the per-tenant tagging.
        val sample = startDocumentTimer()
        var outcome = "success"
        try {
            // Generate fake document
            val documentId = DocumentKey.generate()
            val filename = request.filename ?: "document-${request.id.value}.pdf"
            // Single timestamp for both the blob and the metadata row so they land in
            // the same document_content / documents monthly partition (#738).
            val createdAt = EpistolaClock.offsetDateTime()

            // Store fake PDF content in the document content store
            localContentStore.put(
                ContentKey.document(request.tenantKey, documentId),
                ByteArrayInputStream(fakePdfBytes),
                "application/pdf",
                fakePdfBytes.size.toLong(),
                createdAt,
            )

            // Mirror production: per-tenant document-size summary.
            recordDocumentSize(request, fakePdfBytes.size.toLong())

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
                emitTerminalResult(
                    request = request,
                    status = ResultStatus.COMPLETED,
                    documentId = documentId,
                    sizeBytes = fakePdfBytes.size.toLong(),
                    contentType = "application/pdf",
                    error = null,
                )

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
                        :createdAt, :createdBy
                    )
                    """,
                )
                    .bind("id", documentId)
                    .bind("tenantId", request.tenantKey)
                    .bind("templateId", request.templateKey)
                    .bind("variantId", request.variantKey)
                    .bind("versionId", request.versionKey ?: request.environmentKey) // Use either
                    .bind("filename", filename)
                    .bind("correlationId", request.correlationId)
                    .bind("sizeBytes", fakePdfBytes.size.toLong())
                    .bind("createdAt", createdAt)
                    .bind("createdBy", currentUserIdOrNull()?.value)
                    .execute()
            }

            // Check if batch is complete and finalize if so
            request.batchId?.let { batchId ->
                finalizeBatchIfComplete(batchId)
            }

            logger.debug("FAKE document {} created for request {}", documentId.value, request.id.value)
        } catch (e: Exception) {
            outcome = "failure"
            logger.error("FAKE execution failed for request {}: {}", request.id.value, e.message, e)
            markRequestFailed(request, e.message)
            request.batchId?.let { finalizeBatchIfComplete(it) }
        } finally {
            // Mirror production: per-tenant generation-duration timer.
            sample.stop(documentDurationTimer(request, outcome, "fake"))
        }
    }

    private fun markRequestFailed(request: DocumentGenerationRequest, errorMessage: String?) {
        val expiresAtInterval = "$localRetentionDays days"
        val updated = localJdbi.withHandle<Int, Exception> { handle ->
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
                .bind("requestId", request.id)
                .bind("errorMessage", errorMessage?.take(1000))
                .bind("expiresAt", expiresAtInterval)
                .execute()
        }
        if (updated > 0) {
            emitTerminalResult(
                request = request,
                status = ResultStatus.FAILED,
                documentId = null,
                sizeBytes = null,
                contentType = null,
                error = errorMessage ?: "Unknown error",
            )
        }
    }

    /**
     * Mirror of [DocumentGenerationExecutor.emitTerminalResult] — kept here so
     * tests using this fake executor exercise the same emit path as production.
     * Failures are swallowed, same rationale.
     */
    private fun emitTerminalResult(
        request: DocumentGenerationRequest,
        status: ResultStatus,
        documentId: DocumentKey?,
        sizeBytes: Long?,
        contentType: String?,
        error: String?,
    ) {
        try {
            localMediator.send(
                EmitGenerationResult(
                    request = request,
                    status = status,
                    documentId = documentId,
                    sizeBytes = sizeBytes,
                    contentType = contentType,
                    error = error,
                    completedAt = EpistolaClock.offsetDateTime(),
                ),
            )
        } catch (e: Exception) {
            logger.error(
                "FAKE: failed to emit generation result for request {} (status {}): {}",
                request.id.value,
                status,
                e.message,
                e,
            )
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

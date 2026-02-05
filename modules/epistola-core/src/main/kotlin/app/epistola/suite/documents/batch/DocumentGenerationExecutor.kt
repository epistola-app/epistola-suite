package app.epistola.suite.documents.batch

import app.epistola.suite.common.ids.BatchId
import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.documents.model.Document
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.tenants.queries.GetTenant
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime

/**
 * Executes document generation jobs.
 *
 * After V9 migration, each request represents a SINGLE document.
 * Concurrency is controlled at the JobPoller level, so this executor
 * simply processes one request = one document.
 */
@Component
class DocumentGenerationExecutor(
    private val jdbi: Jdbi,
    private val generationService: GenerationService,
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    private val retentionDays: Int,
    @Value("\${epistola.generation.documents.max-size-mb:50}")
    private val maxDocumentSizeMb: Long,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Execute a document generation job.
     *
     * After V9 migration, each request contains all data needed to generate ONE document.
     * Simply generate the document, save it, and mark the request as completed.
     * If part of a batch, update batch progress.
     */
    fun execute(request: DocumentGenerationRequest) {
        // Check if request was cancelled before starting
        if (request.status == RequestStatus.CANCELLED) {
            logger.info("Request {} was cancelled, skipping execution", request.id.value)
            return
        }

        logger.info(
            "Processing request {} for template {}/{}/{}",
            request.id.value,
            request.templateId.value,
            request.variantId.value,
            request.versionId?.value ?: request.environmentId?.value,
        )

        try {
            // Generate the document
            val document = generateDocument(request)

            // Save document and mark request as completed
            saveDocumentAndMarkCompleted(request.id, document)

            // Update batch progress if this request is part of a batch
            request.batchId?.let { batchId ->
                updateBatchProgress(batchId)
            }

            logger.info("Request {} completed successfully: document {}", request.id.value, document.id.value)
        } catch (e: Exception) {
            logger.error("Failed to process request {}: {}", request.id.value, e.message, e)
            markRequestFailed(request.id, e.message ?: "Unknown error")

            // Update batch progress even on failure
            request.batchId?.let { batchId ->
                updateBatchProgress(batchId)
            }
        }
    }

    /**
     * Generate a PDF document for the given request.
     */
    private fun generateDocument(request: DocumentGenerationRequest): Document {
        logger.debug(
            "Generating document for request {} (template {}/{}/{})",
            request.id.value,
            request.templateId.value,
            request.variantId.value,
            request.versionId?.value ?: request.environmentId?.value,
        )

        // 1. Resolve template version
        val version = if (request.versionId != null) {
            // Use explicit version
            mediator.query(
                GetVersion(
                    tenantId = request.tenantId,
                    templateId = request.templateId,
                    variantId = request.variantId,
                    versionId = request.versionId,
                ),
            ) ?: throw IllegalStateException("Version ${request.versionId} not found")
        } else {
            // Use environment to determine active version
            mediator.query(
                GetActiveVersion(
                    tenantId = request.tenantId,
                    templateId = request.templateId,
                    variantId = request.variantId,
                    environmentId = request.environmentId!!,
                ),
            ) ?: throw IllegalStateException("No active version for environment ${request.environmentId}")
        }

        // 2. Validate template model exists
        val templateModel = version.templateModel
            ?: throw IllegalStateException("Version ${version.id} has no template model")

        // 3. Fetch template to get default theme
        val template = mediator.query(
            GetDocumentTemplate(
                tenantId = request.tenantId,
                id = request.templateId,
            ),
        ) ?: throw IllegalStateException("Template ${request.templateId} not found")

        // 4. Fetch tenant to get default theme (ultimate fallback)
        val tenant = mediator.query(GetTenant(id = request.tenantId))
            ?: throw IllegalStateException("Tenant ${request.tenantId} not found")

        // 5. Generate PDF
        val outputStream = ByteArrayOutputStream()

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(request.data, Map::class.java) as Map<String, Any?>
        generationService.renderPdf(request.tenantId, templateModel, dataMap, outputStream, template.themeId, tenant.defaultThemeId)

        val pdfBytes = outputStream.toByteArray()
        val sizeBytes = pdfBytes.size.toLong()

        // 6. Validate size
        val maxSizeBytes = maxDocumentSizeMb * 1024 * 1024
        if (sizeBytes > maxSizeBytes) {
            throw IllegalStateException("Generated document size ($sizeBytes bytes) exceeds maximum ($maxSizeBytes bytes)")
        }

        // 7. Generate filename if not provided
        val filename = request.filename ?: "document-${request.id.value}.pdf"

        // 8. Create Document entity
        return Document(
            id = DocumentId.generate(),
            tenantId = request.tenantId,
            templateId = request.templateId,
            variantId = request.variantId,
            versionId = version.id,
            filename = filename,
            correlationId = request.correlationId,
            contentType = "application/pdf",
            sizeBytes = sizeBytes,
            content = pdfBytes,
            createdAt = OffsetDateTime.now(),
            createdBy = null, // TODO: Get from security context when auth is implemented
        )
    }

    /**
     * Save the generated document and mark the request as completed.
     */
    private fun saveDocumentAndMarkCompleted(requestId: GenerationRequestId, document: Document) {
        jdbi.useTransaction<Exception> { handle ->
            // 1. Insert document into database
            handle.createUpdate(
                """
                INSERT INTO documents (
                    id, tenant_id, template_id, variant_id, version_id,
                    filename, correlation_id, content_type, size_bytes, content,
                    created_at, created_by
                )
                VALUES (
                    :id, :tenantId, :templateId, :variantId, :versionId,
                    :filename, :correlationId, :contentType, :sizeBytes, :content,
                    :createdAt, :createdBy
                )
                """,
            )
                .bind("id", document.id)
                .bind("tenantId", document.tenantId)
                .bind("templateId", document.templateId)
                .bind("variantId", document.variantId)
                .bind("versionId", document.versionId)
                .bind("filename", document.filename)
                .bind("correlationId", document.correlationId)
                .bind("contentType", document.contentType)
                .bind("sizeBytes", document.sizeBytes)
                .bind("content", document.content)
                .bind("createdAt", document.createdAt)
                .bind("createdBy", document.createdBy?.value)
                .execute()

            logger.debug("Created document {} for tenant {}", document.id.value, document.tenantId.value)

            // 2. Update generation request with document_id and status
            val expiresAtInterval = "$retentionDays days"
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'COMPLETED',
                    document_id = :documentId,
                    completed_at = NOW(),
                    expires_at = NOW() + :expiresAt::interval
                WHERE id = :requestId
                """,
            )
                .bind("requestId", requestId)
                .bind("documentId", document.id)
                .bind("expiresAt", expiresAtInterval)
                .execute()
        }
    }

    /**
     * Mark a request as failed with an error message.
     */
    private fun markRequestFailed(requestId: GenerationRequestId, errorMessage: String) {
        val expiresAtInterval = "$retentionDays days"
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = 'FAILED',
                    error_message = :errorMessage,
                    completed_at = NOW(),
                    expires_at = NOW() + :expiresAt::interval
                WHERE id = :requestId
                """,
            )
                .bind("requestId", requestId)
                .bind("errorMessage", errorMessage.take(1000))
                .bind("expiresAt", expiresAtInterval)
                .execute()
        }
    }

    /**
     * Update batch progress by incrementing completed/failed counts.
     *
     * Atomically increments counts and sets completed_at when all requests are done.
     * Uses a single UPDATE with aggregate query for consistency.
     */
    private fun updateBatchProgress(batchId: BatchId) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_batches b
                SET
                    completed_count = (
                        SELECT COUNT(*)
                        FROM document_generation_requests
                        WHERE batch_id = :batchId AND status = 'COMPLETED'
                    ),
                    failed_count = (
                        SELECT COUNT(*)
                        FROM document_generation_requests
                        WHERE batch_id = :batchId AND status = 'FAILED'
                    ),
                    completed_at = CASE
                        WHEN (
                            SELECT COUNT(*)
                            FROM document_generation_requests
                            WHERE batch_id = :batchId
                              AND status IN ('COMPLETED', 'FAILED')
                        ) = b.total_count
                        THEN NOW()
                        ELSE b.completed_at
                    END
                WHERE b.id = :batchId
                """,
            )
                .bind("batchId", batchId)
                .execute()
        }

        logger.debug("Updated batch progress for batch {}", batchId.value)
    }
}

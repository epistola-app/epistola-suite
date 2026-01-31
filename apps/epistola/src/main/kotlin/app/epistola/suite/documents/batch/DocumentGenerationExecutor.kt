package app.epistola.suite.documents.batch

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.documents.model.Document
import app.epistola.suite.documents.model.DocumentGenerationItem
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Executes document generation jobs by processing items concurrently using virtual threads.
 *
 * Uses a semaphore to limit concurrent processing to avoid overwhelming the system.
 * Each item is processed independently, allowing partial batch completion on failures.
 */
@Component
class DocumentGenerationExecutor(
    private val jdbi: Jdbi,
    private val generationService: GenerationService,
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
    @Value("\${epistola.generation.async.concurrency:10}")
    private val concurrency: Int,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    private val retentionDays: Int,
    @Value("\${epistola.generation.documents.max-size-mb:50}")
    private val maxDocumentSizeMb: Long,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newVirtualThreadPerTaskExecutor()

    /**
     * Execute a document generation job.
     *
     * Processes all pending items for the request concurrently (limited by semaphore).
     * Each item succeeds or fails independently.
     */
    fun execute(request: DocumentGenerationRequest) {
        // Check if request was cancelled before starting
        if (isRequestCancelled(request.id)) {
            logger.info("Request {} was cancelled, skipping execution", request.id)
            return
        }

        val items = fetchPendingItems(request.id)
        logger.info("Processing {} items for request {}", items.size, request.id)

        if (items.isEmpty()) {
            // No items to process, finalize immediately
            finalizeRequest(request.id)
            return
        }

        // Track cancellation state
        val cancelled = AtomicBoolean(false)

        // Process items concurrently with virtual threads (limited by semaphore)
        val semaphore = Semaphore(concurrency)
        val futures = items.map { item ->
            CompletableFuture.runAsync({
                if (cancelled.get()) {
                    return@runAsync
                }

                semaphore.acquire()
                try {
                    // Check for cancellation before processing each item
                    if (isRequestCancelled(request.id)) {
                        cancelled.set(true)
                        return@runAsync
                    }
                    processItem(item, request.tenantId)
                } finally {
                    semaphore.release()
                }
            }, executor)
        }

        // Wait for all items to complete
        CompletableFuture.allOf(*futures.toTypedArray()).join()

        // Finalize request status (handles cancellation case too)
        finalizeRequest(request.id)
    }

    /**
     * Fetch all pending items for a request and mark them as IN_PROGRESS atomically.
     */
    private fun fetchPendingItems(requestId: UUID): List<DocumentGenerationItem> = jdbi.inTransaction<List<DocumentGenerationItem>, Exception> { handle ->
        handle.createQuery(
            """
                UPDATE document_generation_items
                SET status = 'IN_PROGRESS', started_at = NOW()
                WHERE request_id = :requestId
                  AND status = 'PENDING'
                RETURNING id, request_id, template_id, variant_id, version_id, environment_id,
                          data, filename, correlation_id, status, error_message, document_id,
                          created_at, started_at, completed_at
                """,
        )
            .bind("requestId", requestId)
            .mapTo<DocumentGenerationItem>()
            .list()
    }

    /**
     * Check if a request has been cancelled.
     */
    private fun isRequestCancelled(requestId: UUID): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createQuery(
            """
                SELECT status = 'CANCELLED' FROM document_generation_requests WHERE id = :requestId
                """,
        )
            .bind("requestId", requestId)
            .mapTo<Boolean>()
            .one()
    }

    /**
     * Process a single item: resolve version, generate PDF, save document.
     */
    private fun processItem(item: DocumentGenerationItem, tenantId: UUID) {
        try {
            val document = generateDocument(item, tenantId)
            saveDocumentAndMarkComplete(item.id, document)
        } catch (e: Exception) {
            logger.error("Failed to process item {}: {}", item.id, e.message, e)
            markItemFailed(item.id, e.message ?: "Unknown error")
        }
    }

    /**
     * Generate a PDF document for the given item.
     */
    private fun generateDocument(item: DocumentGenerationItem, tenantId: UUID): Document {
        logger.debug(
            "Processing item {} for template {}/{}/{}",
            item.id,
            item.templateId,
            item.variantId,
            item.versionId ?: item.environmentId,
        )

        // 1. Resolve template version
        val version = if (item.versionId != null) {
            // Use explicit version
            mediator.query(
                GetVersion(
                    tenantId = tenantId,
                    templateId = item.templateId,
                    variantId = item.variantId,
                    versionId = item.versionId,
                ),
            ) ?: throw IllegalStateException("Version ${item.versionId} not found")
        } else {
            // Use environment to determine active version
            mediator.query(
                GetActiveVersion(
                    tenantId = tenantId,
                    templateId = item.templateId,
                    variantId = item.variantId,
                    environmentId = item.environmentId!!,
                ),
            ) ?: throw IllegalStateException("No active version for environment ${item.environmentId}")
        }

        // 2. Validate template model exists
        val templateModel = version.templateModel
            ?: throw IllegalStateException("Version ${version.id} has no template model")

        // 3. Generate PDF
        val outputStream = ByteArrayOutputStream()

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(item.data, Map::class.java) as Map<String, Any?>
        generationService.renderPdf(templateModel, dataMap, outputStream)

        val pdfBytes = outputStream.toByteArray()
        val sizeBytes = pdfBytes.size.toLong()

        // 4. Validate size
        val maxSizeBytes = maxDocumentSizeMb * 1024 * 1024
        if (sizeBytes > maxSizeBytes) {
            throw IllegalStateException("Generated document size ($sizeBytes bytes) exceeds maximum ($maxSizeBytes bytes)")
        }

        // 5. Generate filename if not provided
        val filename = item.filename ?: "document-${item.id}.pdf"

        // 6. Create Document entity
        return Document(
            id = UUIDv7.generate(),
            tenantId = tenantId,
            templateId = item.templateId,
            variantId = item.variantId,
            versionId = version.id,
            filename = filename,
            correlationId = item.correlationId,
            contentType = "application/pdf",
            sizeBytes = sizeBytes,
            content = pdfBytes,
            createdAt = OffsetDateTime.now(),
            createdBy = null, // TODO: Get from security context when auth is implemented
        )
    }

    /**
     * Save the generated document and mark the item as completed.
     */
    private fun saveDocumentAndMarkComplete(itemId: UUID, document: Document) {
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
                .bind("createdBy", document.createdBy)
                .execute()

            logger.debug("Created document {} for tenant {}", document.id, document.tenantId)

            // 2. Update generation item with document_id and status = COMPLETED
            handle.createUpdate(
                """
                UPDATE document_generation_items
                SET status = 'COMPLETED',
                    document_id = :documentId,
                    completed_at = NOW()
                WHERE id = :itemId
                """,
            )
                .bind("documentId", document.id)
                .bind("itemId", itemId)
                .execute()
        }
    }

    /**
     * Mark an item as failed with an error message.
     */
    private fun markItemFailed(itemId: UUID, errorMessage: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_items
                SET status = 'FAILED',
                    error_message = :errorMessage,
                    completed_at = NOW()
                WHERE id = :itemId
                """,
            )
                .bind("itemId", itemId)
                .bind("errorMessage", errorMessage.take(1000))
                .execute()
        }
    }

    /**
     * Finalize the request by calculating final counts and setting status.
     */
    private fun finalizeRequest(requestId: UUID) {
        jdbi.useTransaction<Exception> { handle ->
            // Get current counts
            val counts = handle.createQuery(
                """
                SELECT
                    COUNT(*) FILTER (WHERE status = 'COMPLETED') as completed,
                    COUNT(*) FILTER (WHERE status = 'FAILED') as failed,
                    COUNT(*) as total
                FROM document_generation_items
                WHERE request_id = :requestId
                """,
            )
                .bind("requestId", requestId)
                .mapToMap()
                .one()

            val completed = (counts["completed"] as Number).toInt()
            val failed = (counts["failed"] as Number).toInt()
            val total = (counts["total"] as Number).toInt()

            // Check if request was cancelled
            val currentStatus = handle.createQuery(
                """
                SELECT status FROM document_generation_requests WHERE id = :requestId
                """,
            )
                .bind("requestId", requestId)
                .mapTo<String>()
                .one()

            val status = when {
                currentStatus == "CANCELLED" -> RequestStatus.CANCELLED
                failed == total -> RequestStatus.FAILED
                else -> RequestStatus.COMPLETED
            }

            // Calculate expiration date
            val expiresAtInterval = "$retentionDays days"

            handle.createUpdate(
                """
                UPDATE document_generation_requests
                SET status = :status,
                    completed_count = :completed,
                    failed_count = :failed,
                    completed_at = NOW(),
                    expires_at = NOW() + :expiresAt::interval
                WHERE id = :requestId
                """,
            )
                .bind("requestId", requestId)
                .bind("status", status.name)
                .bind("completed", completed)
                .bind("failed", failed)
                .bind("expiresAt", expiresAtInterval)
                .execute()

            logger.info("Request {} completed: {} succeeded, {} failed", requestId, completed, failed)
        }
    }
}

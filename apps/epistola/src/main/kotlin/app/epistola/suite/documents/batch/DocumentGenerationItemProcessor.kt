package app.epistola.suite.documents.batch

import app.epistola.suite.documents.model.Document
import app.epistola.suite.documents.model.DocumentGenerationItem
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.batch.item.ItemProcessor
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime

/**
 * Spring Batch ItemProcessor for document generation.
 *
 * Processes each item by:
 * 1. Resolving the template version (by explicit ID or environment)
 * 2. Validating data against schema
 * 3. Generating the PDF
 * 4. Creating a Document entity
 *
 * If processing fails, returns null and updates the item's error_message.
 *
 * @param mediator Mediator for queries
 * @param generationService Service for PDF generation
 * @param objectMapper Jackson ObjectMapper
 * @param jdbi JDBI for database updates
 * @param maxDocumentSizeMb Maximum allowed document size in MB
 */
class DocumentGenerationItemProcessor(
    private val mediator: Mediator,
    private val generationService: GenerationService,
    private val objectMapper: ObjectMapper,
    private val jdbi: Jdbi,
    private val maxDocumentSizeMb: Long,
) : ItemProcessor<DocumentGenerationItem, Document?> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun process(item: DocumentGenerationItem): Document? {
        return try {
            logger.debug("Processing item {} for template {}/{}/{}", item.id, item.templateId, item.variantId, item.versionId ?: item.environmentId)

            // 1. Resolve template version
            val version = if (item.versionId != null) {
                // Use explicit version
                mediator.query(GetVersion(
                    tenantId = getTenantId(item),
                    templateId = item.templateId,
                    variantId = item.variantId,
                    versionId = item.versionId
                )) ?: throw IllegalStateException("Version ${item.versionId} not found")
            } else {
                // Use environment to determine active version
                val activation = mediator.query(GetActiveVersion(
                    tenantId = getTenantId(item),
                    templateId = item.templateId,
                    variantId = item.variantId,
                    environmentId = item.environmentId!!
                )) ?: throw IllegalStateException("No active version for environment ${item.environmentId}")

                mediator.query(GetVersion(
                    tenantId = getTenantId(item),
                    templateId = item.templateId,
                    variantId = item.variantId,
                    versionId = activation.versionId
                )) ?: throw IllegalStateException("Active version ${activation.versionId} not found")
            }

            // 2. Validate template model exists
            val templateModel = version.templateModel
                ?: throw IllegalStateException("Version ${version.id} has no template model")

            // 3. Validate data (if schema exists)
            // Note: Schema validation is handled by GenerationService during rendering
            // We could add explicit validation here if needed

            // 4. Generate PDF
            val outputStream = ByteArrayOutputStream()
            val dataMap = objectMapper.convertValue(item.data, Map::class.java) as Map<String, Any?>
            generationService.renderPdf(templateModel, dataMap, outputStream)

            val pdfBytes = outputStream.toByteArray()
            val sizeBytes = pdfBytes.size.toLong()

            // 5. Validate size
            val maxSizeBytes = maxDocumentSizeMb * 1024 * 1024
            if (sizeBytes > maxSizeBytes) {
                throw IllegalStateException("Generated document size ($sizeBytes bytes) exceeds maximum ($maxSizeBytes bytes)")
            }

            // 6. Generate filename if not provided
            val filename = item.filename ?: "document-${item.id}.pdf"

            // 7. Create Document entity
            Document(
                id = 0, // Will be assigned by database
                tenantId = getTenantId(item),
                templateId = item.templateId,
                variantId = item.variantId,
                versionId = version.id,
                filename = filename,
                contentType = "application/pdf",
                sizeBytes = sizeBytes,
                content = pdfBytes,
                createdAt = OffsetDateTime.now(),
                createdBy = null // TODO: Get from security context when auth is implemented
            )
        } catch (e: Exception) {
            // Log error and update item status
            logger.error("Failed to process item {}: {}", item.id, e.message, e)
            updateItemError(item.id, e.message ?: "Unknown error")
            null // Returning null signals failure to Spring Batch
        }
    }

    private fun getTenantId(item: DocumentGenerationItem): Long {
        return jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery(
                """
                SELECT tenant_id
                FROM document_generation_requests
                WHERE id = :requestId
                """
            )
                .bind("requestId", item.requestId)
                .mapTo(Long::class.java)
                .one()
        }
    }

    private fun updateItemError(itemId: java.util.UUID, errorMessage: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE document_generation_items
                SET status = 'FAILED',
                    error_message = :errorMessage,
                    completed_at = NOW()
                WHERE id = :itemId
                """
            )
                .bind("itemId", itemId)
                .bind("errorMessage", errorMessage.take(1000)) // Limit error message length
                .execute()
        }
    }
}

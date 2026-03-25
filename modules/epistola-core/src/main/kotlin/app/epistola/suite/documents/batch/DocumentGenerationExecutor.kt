package app.epistola.suite.documents.batch

import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.model.AssemblyStatus
import app.epistola.suite.documents.model.Document
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.services.BatchAssemblyService
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.tenants.queries.GetTenant
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
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
    private val contentStore: ContentStore,
    private val meterRegistry: MeterRegistry,
    private val batchAssemblyService: BatchAssemblyService,
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

        val templateName = request.templateKey.value
        val path = "unknown" // will be determined during generation

        logger.info(
            "Processing request {} for template {}/{}/{}",
            request.id.value,
            templateName,
            request.variantKey.value,
            request.versionKey?.value ?: request.environmentKey?.value,
        )

        val sample = Timer.start(meterRegistry)
        var outcome = "success"
        var renderPath = "unknown"
        try {
            // Generate the document
            val (document, pdfBytes, usedPath) = generateDocument(request)
            renderPath = usedPath

            // Store content first — orphaned blob is harmless, missing content is not
            contentStore.put(
                ContentKey.document(document.tenantKey, document.id),
                ByteArrayInputStream(pdfBytes),
                document.contentType,
                document.sizeBytes,
            )

            // Record document size
            DistributionSummary.builder("epistola.generation.document.size.bytes")
                .tag("template", templateName)
                .register(meterRegistry)
                .record(document.sizeBytes.toDouble())

            // Save document metadata and mark request as completed
            saveDocumentAndMarkCompleted(request.id, document)

            // Check if batch is complete and finalize if so
            request.batchId?.let { batchId ->
                finalizeBatchIfComplete(batchId)
            }

            logger.info("Request {} completed successfully: document {}", request.id.value, document.id.value)
        } catch (e: Exception) {
            outcome = "failure"
            logger.error("Failed to process request {}: {}", request.id.value, e.message, e)
            markRequestFailed(request.id, e.message ?: "Unknown error")

            // Check if batch is complete even on failure
            request.batchId?.let { batchId ->
                finalizeBatchIfComplete(batchId)
            }
        } finally {
            sample.stop(
                Timer.builder("epistola.generation.document.duration")
                    .tag("outcome", outcome)
                    .tag("template", templateName)
                    .tag("path", renderPath)
                    .register(meterRegistry),
            )
        }
    }

    /**
     * Generate a PDF document for the given request.
     * Returns the document metadata, raw PDF bytes, and the render path used (snapshot/legacy).
     */
    private fun generateDocument(request: DocumentGenerationRequest): Triple<Document, ByteArray, String> {
        logger.debug(
            "Generating document for request {} (template {}/{}/{})",
            request.id.value,
            request.templateKey.value,
            request.variantKey.value,
            request.versionKey?.value ?: request.environmentKey?.value,
        )

        // Build composite IDs
        val tenantId = TenantId(request.tenantKey)
        val templateId = TemplateId(request.templateKey, tenantId)
        val variantId = VariantId(request.variantKey, templateId)

        // 1. Resolve template version
        val version = if (request.versionKey != null) {
            // Use explicit version
            val versionId = VersionId(request.versionKey, variantId)
            mediator.query(GetVersion(versionId))
                ?: throw IllegalStateException("Version ${request.versionKey} not found")
        } else {
            // Use environment to determine active version
            val environmentId = EnvironmentId(request.environmentKey!!, tenantId)
            mediator.query(GetActiveVersion(variantId, environmentId))
                ?: throw IllegalStateException("No active version for environment ${request.environmentKey}")
        }

        // 2. Get template model
        val templateModel = version.templateModel

        // 3. Fetch template to get default theme
        val template = mediator.query(GetDocumentTemplate(templateId))
            ?: throw IllegalStateException("Template ${request.templateKey} not found")

        // 4. Fetch tenant to get default theme (ultimate fallback)
        val tenant = mediator.query(GetTenant(id = request.tenantKey))
            ?: throw IllegalStateException("Tenant ${request.tenantKey} not found")

        // 5. Generate PDF
        val outputStream = ByteArrayOutputStream()

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(request.data, Map::class.java) as Map<String, Any?>
        val metadata = PdfMetadata(
            title = template.name,
            author = tenant.name,
        )
        val assetResolver = AssetResolver { assetId ->
            mediator.query(GetAssetContent(request.tenantKey, AssetKey.of(assetId)))
                ?.let { AssetResolution(it.content, it.mediaType.mimeType) }
        }

        // Use frozen snapshot for published versions, live resolution for legacy versions
        val resolvedTheme = version.resolvedTheme
        val renderingDefaultsVersion = version.renderingDefaultsVersion
        val renderPath: String
        if (resolvedTheme != null && renderingDefaultsVersion != null) {
            renderPath = "snapshot"
            // Deterministic path: use frozen theme + rendering defaults from publish time
            val renderingDefaults = RenderingDefaults.forVersion(renderingDefaultsVersion)
            val metadataWithEngine = metadata.copy(engineVersion = renderingDefaults.engineVersionString())
            generationService.renderPdfWithSnapshot(
                templateModel,
                dataMap,
                outputStream,
                resolvedTheme,
                renderingDefaults,
                metadataWithEngine,
                pdfaCompliant = template.pdfaEnabled,
                assetResolver = assetResolver,
            )
        } else {
            renderPath = "legacy"
            // Legacy path: live theme resolution (backward compatible for pre-V16 published versions)
            val metadataWithEngine = metadata.copy(
                engineVersion = RenderingDefaults.CURRENT.engineVersionString(),
            )
            generationService.renderPdf(
                request.tenantKey,
                templateModel,
                dataMap,
                outputStream,
                template.themeKey,
                tenant.defaultThemeKey,
                metadataWithEngine,
                pdfaCompliant = template.pdfaEnabled,
                assetResolver = assetResolver,
            )
        }

        val pdfBytes = outputStream.toByteArray()
        val sizeBytes = pdfBytes.size.toLong()

        // 6. Validate size
        val maxSizeBytes = maxDocumentSizeMb * 1024 * 1024
        if (sizeBytes > maxSizeBytes) {
            throw IllegalStateException("Generated document size ($sizeBytes bytes) exceeds maximum ($maxSizeBytes bytes)")
        }

        // 7. Generate filename if not provided
        val filename = request.filename ?: "document-${request.id.value}.pdf"

        // 8. Create Document entity (metadata only — content stored separately)
        val document = Document(
            id = DocumentKey.generate(),
            tenantKey = request.tenantKey,
            templateKey = request.templateKey,
            variantKey = request.variantKey,
            versionKey = version.id,
            filename = filename,
            correlationId = request.correlationKey,
            contentType = "application/pdf",
            sizeBytes = sizeBytes,
            createdAt = OffsetDateTime.now(),
            createdBy = null, // TODO: Get from security context when auth is implemented
        )
        return Triple(document, pdfBytes, renderPath)
    }

    /**
     * Save the generated document and mark the request as completed.
     */
    private fun saveDocumentAndMarkCompleted(requestId: GenerationRequestKey, document: Document) {
        jdbi.useTransaction<Exception> { handle ->
            // 1. Claim completion — skip if the request was cancelled during processing
            val expiresAtInterval = "$retentionDays days"
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
                .bind("requestId", requestId)
                .bind("documentId", document.id)
                .bind("expiresAt", expiresAtInterval)
                .execute()

            if (updated == 0) {
                logger.info("Request {} was cancelled during processing, skipping document storage", requestId)
                return@useTransaction
            }

            // 2. Insert document metadata into database (content stored in ContentStore)
            handle.createUpdate(
                """
                INSERT INTO documents (
                    id, tenant_key, template_key, variant_key, version_key,
                    filename, correlation_id, content_type, size_bytes,
                    created_at, created_by
                )
                VALUES (
                    :id, :tenantId, :templateId, :variantId, :versionId,
                    :filename, :correlationId, :contentType, :sizeBytes,
                    :createdAt, :createdBy
                )
                """,
            )
                .bind("id", document.id)
                .bind("tenantId", document.tenantKey)
                .bind("templateId", document.templateKey)
                .bind("variantId", document.variantKey)
                .bind("versionId", document.versionKey)
                .bind("filename", document.filename)
                .bind("correlationId", document.correlationId)
                .bind("contentType", document.contentType)
                .bind("sizeBytes", document.sizeBytes)
                .bind("createdAt", document.createdAt)
                .bind("createdBy", document.createdBy?.value)
                .execute()

            logger.debug("Created document {} for tenant {}", document.id.value, document.tenantKey.value)
        }
    }

    /**
     * Mark a request as failed with an error message.
     */
    private fun markRequestFailed(requestId: GenerationRequestKey, errorMessage: String) {
        val expiresAtInterval = "$retentionDays days"
        jdbi.useHandle<Exception> { handle ->
            val updated = handle.createUpdate(
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
                .bind("errorMessage", errorMessage.take(1000))
                .bind("expiresAt", expiresAtInterval)
                .execute()

            if (updated == 0) {
                logger.info("Request {} was cancelled during processing, not marking as failed", requestId)
            }
        }
    }

    /**
     * Calculate current batch counts on-demand.
     */
    private data class BatchCounts(
        val completed: Int,
        val failed: Int,
        val pending: Int,
        val inProgress: Int,
    ) {
        val total: Int get() = completed + failed + pending + inProgress
        val isComplete: Boolean get() = pending == 0 && inProgress == 0
    }

    private fun getBatchCounts(batchId: BatchKey): BatchCounts = jdbi.withHandle<BatchCounts, Exception> { handle ->
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

    /**
     * Check if batch is complete and finalize if so.
     *
     * Calculates final counts and stores them when all requests are done.
     * Only finalizes once (idempotent - checks completed_at IS NULL).
     */
    private fun finalizeBatchIfComplete(batchId: BatchKey) {
        val counts = getBatchCounts(batchId)

        if (counts.isComplete) {
            jdbi.useHandle<Exception> { handle ->
                // Get download formats and tenant before updating
                val batchInfo = handle.createQuery(
                    """
                    SELECT tenant_key, download_formats
                    FROM document_generation_batches
                    WHERE id = :batchId AND completed_at IS NULL
                    """,
                )
                    .bind("batchId", batchId)
                    .map { rs, _ ->
                        Pair(rs.getString("tenant_key"), rs.getString("download_formats"))
                    }
                    .findOne()
                    .orElse(null) ?: return@useHandle // Already finalized

                val (tenantKeyStr, formatsJson) = batchInfo
                val hasDownloadFormats = formatsJson != null && formatsJson != "[]" && formatsJson.isNotBlank()

                val assemblyStatus = if (hasDownloadFormats && counts.failed == 0) {
                    AssemblyStatus.PENDING
                } else {
                    AssemblyStatus.NONE
                }

                val updated = handle.createUpdate(
                    """
                    UPDATE document_generation_batches
                    SET
                        final_completed_count = :completed,
                        final_failed_count = :failed,
                        assembly_status = :assemblyStatus,
                        completed_at = NOW()
                    WHERE id = :batchId
                      AND completed_at IS NULL
                    """,
                )
                    .bind("batchId", batchId)
                    .bind("completed", counts.completed)
                    .bind("failed", counts.failed)
                    .bind("assemblyStatus", assemblyStatus.name)
                    .execute()

                if (updated > 0) {
                    logger.info(
                        "Batch {} completed: {} succeeded, {} failed",
                        batchId.value,
                        counts.completed,
                        counts.failed,
                    )

                    // Trigger async assembly if download formats were requested and no failures
                    if (assemblyStatus == AssemblyStatus.PENDING) {
                        val tenantKey = TenantKey.of(tenantKeyStr)
                        batchAssemblyService.assembleDownloads(tenantKey, batchId)
                    }
                }
            }
        }
    }
}

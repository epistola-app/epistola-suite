package app.epistola.suite.documents.batch

import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.model.Document
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.fonts.fontFamilyResolver
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.activations.GetActiveVersion
import app.epistola.suite.templates.queries.versions.GetLatestPublishedVersion
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.queries.GetTenant
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
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
    private val schemaValidator: JsonSchemaValidator,
    private val contentStore: ContentStore,
    private val meterRegistry: MeterRegistry,
    private val fontSnapshotVerifier: app.epistola.suite.fonts.FontSnapshotVerifier,
    private val fontByteCache: app.epistola.suite.fonts.FontByteCache,
    private val localeResolver: TenantLocaleResolver,
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
            val transitioned = saveDocumentAndMarkCompleted(request.id, document)

            // Emit a row to generation_results so /generation/collect can deliver it.
            // Only emit when we actually transitioned the request — a CANCELLED
            // request that was processed in parallel must not produce a phantom result.
            // Note: separate transaction from the UPDATE; an emit failure here logs
            // an error but doesn't roll back the UPDATE. Acceptable for v0.3 (project
            // is pre-production, loss is detectable, recovery is operator-driven).
            if (transitioned) {
                emitTerminalResult(
                    request = request,
                    status = ResultStatus.COMPLETED,
                    documentId = document.id,
                    sizeBytes = document.sizeBytes,
                    contentType = document.contentType,
                    error = null,
                )
            }

            // Check if batch is complete and finalize if so
            request.batchId?.let { batchId ->
                finalizeBatchIfComplete(batchId)
            }

            logger.info("Request {} completed successfully: document {}", request.id.value, document.id.value)
        } catch (e: Exception) {
            outcome = "failure"
            logger.error("Failed to process request {}: {}", request.id.value, e.message, e)
            val transitioned = markRequestFailed(request.id, e.message ?: "Unknown error")

            if (transitioned) {
                emitTerminalResult(
                    request = request,
                    status = ResultStatus.FAILED,
                    documentId = null,
                    sizeBytes = null,
                    contentType = null,
                    error = e.message ?: "Unknown error",
                )
            }

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
        val catalogId = CatalogId(request.catalogKey, tenantId)
        val templateId = TemplateId(request.templateKey, catalogId)
        val variantId = VariantId(request.variantKey, templateId)

        // 1. Resolve template version
        val version = if (request.versionKey != null) {
            // Use explicit version
            val versionId = VersionId(request.versionKey, variantId)
            mediator.query(GetVersion(versionId))
                ?: throw IllegalStateException("Version ${request.versionKey} not found")
        } else if (request.environmentKey != null) {
            // Use environment to determine active version
            val environmentId = EnvironmentId(request.environmentKey, tenantId)
            mediator.query(GetActiveVersion(variantId, environmentId))
                ?: throw IllegalStateException("No active version for environment ${request.environmentKey}")
        } else {
            // Fallback: latest published version (safety net — handler should have resolved this)
            mediator.query(GetLatestPublishedVersion(variantId))
                ?: throw IllegalStateException("No published version found for template ${request.templateKey} variant ${request.variantKey}. Import a catalog or publish a version first.")
        }

        // 2. Get template model
        val templateModel = version.templateModel

        // 3. Fetch template to get default theme
        val template = mediator.query(GetDocumentTemplate(templateId))
            ?: throw IllegalStateException("Template ${request.templateKey} not found")

        // 4. Fetch tenant to get default theme (ultimate fallback)
        val tenant = mediator.query(GetTenant(id = request.tenantKey))
            ?: throw IllegalStateException("Tenant ${request.tenantKey} not found")

        // 5. Validate data against contract schema (if defined)
        val dataModel: ObjectNode? = version.contractVersion?.let { cv ->
            val contractVersion = mediator.query(
                app.epistola.suite.templates.contracts.queries.GetContractVersion(
                    id = app.epistola.suite.common.ids.ContractVersionId(
                        cv,
                        app.epistola.suite.common.ids.TemplateId(
                            request.templateKey,
                            app.epistola.suite.common.ids.CatalogId(request.catalogKey, app.epistola.suite.common.ids.TenantId(request.tenantKey)),
                        ),
                    ),
                ),
            )
            contractVersion?.dataModel
        }
        if (dataModel != null) {
            val errors = schemaValidator.validate(dataModel, request.data)
            if (errors.isNotEmpty()) {
                val errorMessages = errors.joinToString("; ") { "${it.path}: ${it.message}" }
                throw IllegalArgumentException("Data validation failed: $errorMessages")
            }
        }

        // 6. Generate PDF
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
        // Owning catalog for unqualified font refs: same cascade as theme
        // resolution / asset binding (template theme catalog → tenant default
        // theme catalog → the tenant's default catalog).
        val owningCatalogKey =
            template.themeCatalogKey ?: tenant.defaultThemeCatalogKey ?: CatalogKey.DEFAULT
        val fontResolver = fontFamilyResolver(request.tenantKey, owningCatalogKey, fontByteCache)

        // Resolve formatting culture via variant attribute → tenant default → app default
        val culture = localeResolver.resolveCulture(tenant, variantId)

        // Use frozen snapshot for published versions, live resolution for legacy versions
        val resolvedTheme = version.resolvedTheme
        val renderingDefaultsVersion = version.renderingDefaultsVersion
        val renderPath: String
        if (resolvedTheme != null && renderingDefaultsVersion != null) {
            renderPath = "snapshot"
            // Deterministic-or-nothing: a published version must render with the
            // exact font bytes pinned at publish. Fail loudly on drift.
            fontSnapshotVerifier.verify(request.tenantKey, owningCatalogKey, resolvedTheme)
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
                fontFamilyResolver = fontResolver,
                culture = culture,
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
                fontFamilyResolver = fontResolver,
                templateCatalogKey = template.themeCatalogKey,
                tenantDefaultThemeCatalogKey = tenant.defaultThemeCatalogKey,
                culture = culture,
            )
        }

        val pdfBytes = outputStream.toByteArray()
        val sizeBytes = pdfBytes.size.toLong()

        // 7. Validate size
        val maxSizeBytes = maxDocumentSizeMb * 1024 * 1024
        if (sizeBytes > maxSizeBytes) {
            throw IllegalStateException("Generated document size ($sizeBytes bytes) exceeds maximum ($maxSizeBytes bytes)")
        }

        // 8. Generate filename if not provided
        val filename = request.filename ?: "document-${request.id.value}.pdf"

        // 9. Create Document entity (metadata only — content stored separately)
        val document = Document(
            id = DocumentKey.generate(),
            tenantKey = request.tenantKey,
            catalogKey = request.catalogKey,
            templateKey = request.templateKey,
            variantKey = request.variantKey,
            versionKey = version.id,
            filename = filename,
            correlationId = request.correlationId,
            contentType = "application/pdf",
            sizeBytes = sizeBytes,
            createdAt = OffsetDateTime.now(),
            // Background generation runs under the JobPoller's SystemUser
            // principal, which is a real seeded `users` row — so the creator is
            // simply the bound principal (a real user for API-triggered
            // generation, SystemUser for background jobs).
            createdBy = currentUserIdOrNull(),
        )
        return Triple(document, pdfBytes, renderPath)
    }

    /**
     * Save the generated document and mark the request as completed.
     *
     * Marking `document_generation_requests.status='COMPLETED'` looks redundant
     * alongside the [emitTerminalResult] row that lands in `generation_results`,
     * but the two records serve different audiences and aren't interchangeable:
     *
     *  - The status field on the request is read by the originating tenant's
     *    `/api/tenants/{id}/generation/jobs?status=COMPLETED` filter, the
     *    `GetGenerationStats` count query, the `JobPoller`'s PENDING claim
     *    (`WHERE status='PENDING'`), the cancellation branch in this same
     *    executor (`status==CANCELLED`), and a number of integration tests.
     *  - The `generation_results` row is consumer-facing — it's what the
     *    streaming `/generation/collect` endpoint delivers to API-key clients.
     *
     * Unifying the two (event-source the request status from the result rows)
     * is a real architectural option but a large change with audit-volume
     * implications and is out of scope for v0.3.
     *
     * @return true when the request transitioned from IN_PROGRESS to COMPLETED;
     *         false when the request had already been CANCELLED and no update was made.
     */
    private fun saveDocumentAndMarkCompleted(requestId: GenerationRequestKey, document: Document): Boolean {
        return jdbi.inTransaction<Boolean, Exception> { handle ->
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
                return@inTransaction false
            }

            // 2. Insert document metadata into database (content stored in ContentStore)
            handle.createUpdate(
                """
                INSERT INTO documents (
                    id, tenant_key, catalog_key, template_key, variant_key, version_key,
                    filename, correlation_id, content_type, size_bytes,
                    created_at, created_by
                )
                VALUES (
                    :id, :tenantId, :catalogKey, :templateId, :variantId, :versionId,
                    :filename, :correlationId, :contentType, :sizeBytes,
                    :createdAt, :createdBy
                )
                """,
            )
                .bind("id", document.id)
                .bind("tenantId", document.tenantKey)
                .bind("catalogKey", document.catalogKey)
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
            true
        }
    }

    /**
     * Mark a request as failed with an error message.
     *
     * @return true when the request transitioned to FAILED;
     *         false when the request had already been CANCELLED.
     */
    private fun markRequestFailed(requestId: GenerationRequestKey, errorMessage: String): Boolean {
        val expiresAtInterval = "$retentionDays days"
        return jdbi.withHandle<Boolean, Exception> { handle ->
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
                false
            } else {
                true
            }
        }
    }

    /**
     * Dispatch [EmitGenerationResult] for a request that just transitioned to a
     * terminal state. Failures are logged but do not propagate — the
     * `document_generation_requests` UPDATE has already committed and we don't
     * want to rollback that. Lost emits show up as `COMPLETED`/`FAILED` rows
     * with no matching `generation_results` sequence; recoverable by an
     * operator.
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
            mediator.send(
                EmitGenerationResult(
                    request = request,
                    status = status,
                    documentId = documentId,
                    sizeBytes = sizeBytes,
                    contentType = contentType,
                    error = error,
                    completedAt = OffsetDateTime.now(),
                ),
            )
        } catch (e: Exception) {
            logger.error(
                "Failed to emit generation result for request {} (status {}): {}",
                request.id.value,
                status,
                e.message,
                e,
            )
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
                val updated = handle.createUpdate(
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

                if (updated > 0) {
                    logger.info(
                        "Batch {} completed: {} succeeded, {} failed",
                        batchId.value,
                        counts.completed,
                        counts.failed,
                    )
                }
            }
        }
    }
}

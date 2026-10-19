// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.batch

import app.epistola.generation.pdf.AssetResolution
import app.epistola.generation.pdf.AssetResolver
import app.epistola.generation.pdf.PdfMetadata
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.assets.queries.GetAssetContent
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.DraftRef
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
import app.epistola.suite.documents.queries.PreviewVariant
import app.epistola.suite.fonts.fontFamilyResolver
import app.epistola.suite.generation.GenerationService
import app.epistola.suite.generation.collect.commands.EmitGenerationResult
import app.epistola.suite.generation.collect.domain.ResultStatus
import app.epistola.suite.generation.release.ReleaseRef
import app.epistola.suite.generation.release.ReleaseRenderSource
import app.epistola.suite.i18n.TenantLocaleResolver
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.stencils.StencilResolvers
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.JsonSchemaValidator
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.time.EpistolaClock
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
import java.util.UUID

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
    private val contentStore: DocumentContentStore,
    private val meterRegistry: MeterRegistry,
    private val localeResolver: TenantLocaleResolver,
    private val releaseRenderSource: ReleaseRenderSource,
    private val draftRenderer: app.epistola.suite.generation.DraftDocumentRenderer,
    @Value("\${epistola.generation.jobs.retention-days:7}")
    private val retentionDays: Int,
    @Value("\${epistola.generation.documents.max-size-mb:50}")
    private val maxDocumentSizeMb: Long,
    @Value("\${epistola.generation.polling.max-recoveries:3}")
    private val maxRecoveries: Int = 3,
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
            // A render that keeps stopping its node (a template asking for more than a node has) is
            // not handed to the next one again: it fails, with the reason, like any other failure.
            check(request.recoveries < maxRecoveries) {
                "Rendering this document stopped ${request.recoveries} times without finishing (the node rendering it " +
                    "crashed or stopped responding), so it is not tried again. Check the template, then resubmit."
            }
            // Generate the document
            val (document, pdfBytes, usedPath) = generateDocument(request)
            renderPath = usedPath

            // Store content first — orphaned blob is harmless, missing content is not.
            // createdAt = the document's created_at, so the blob lands in the same
            // monthly document_content partition as its metadata and ages out with it.
            contentStore.put(
                ContentKey.document(document.tenantKey, document.id),
                ByteArrayInputStream(pdfBytes),
                document.contentType,
                document.sizeBytes,
                document.createdAt,
            )

            // Record document size
            recordDocumentSize(request, document.sizeBytes)

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
            sample.stop(documentDurationTimer(request, outcome, renderPath))
        }
    }

    /**
     * Starts a generation-duration sample. Exposed so subclasses (e.g. the test
     * fake executor) record the same `epistola.generation.document.duration`
     * timer through the same code path as production.
     */
    protected fun startDocumentTimer(): Timer.Sample = Timer.start(meterRegistry)

    /**
     * Builds the per-tenant `epistola.generation.document.duration` timer. The
     * `tenant` tag (bounded by tenants × templates) lets an operator attribute
     * generation latency / failures per tenant. Single source of the tag set so
     * the real and fake executors stay in parity.
     */
    protected fun documentDurationTimer(
        request: DocumentGenerationRequest,
        outcome: String,
        renderPath: String,
    ): Timer = Timer.builder("epistola.generation.document.duration")
        .tag("tenant", request.tenantKey.value)
        .tag("outcome", outcome)
        .tag("template", request.templateKey.value)
        .tag("path", renderPath)
        .register(meterRegistry)

    /** Records the per-tenant `epistola.generation.document.size.bytes` summary. */
    protected fun recordDocumentSize(request: DocumentGenerationRequest, sizeBytes: Long) {
        DistributionSummary.builder("epistola.generation.document.size.bytes")
            .tag("tenant", request.tenantKey.value)
            .tag("template", request.templateKey.value)
            .register(meterRegistry)
            .record(sizeBytes.toDouble())
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

        if (request.releaseVersion != null) return generateFromRelease(request, request.releaseVersion)
        if (request.draftId != null) return generateFromDraft(request, request.draftId)

        // Accepted before 2.0, against a template version or an environment activation. The catalog
        // release is the only version now, and neither is carried over, so there is nothing to
        // render it from as it was asked.
        throw IllegalStateException(
            "Request ${request.id.value} was accepted before documents were generated from catalog releases " +
                "(${request.versionKey?.let { "template version ${it.value}" } ?: request.environmentKey?.let { "environment '${it.value}'" } ?: "no release"}); resubmit it",
        )
    }

    /**
     * Renders a request bound to a catalog release: everything the document needs comes from that
     * release (and, for other catalogs, the releases its environment deploys or else their latest),
     * nothing from the working copy or a template version.
     */
    private fun generateFromRelease(request: DocumentGenerationRequest, releaseVersion: String): Triple<Document, ByteArray, String> {
        val tenant = mediator.query(GetTenant(id = request.tenantKey))
            ?: throw IllegalStateException("Tenant ${request.tenantKey} not found")
        val inputs = releaseRenderSource.resolve(
            tenant,
            ReleaseRef(request.catalogKey, releaseVersion),
            request.templateKey.value,
            request.variantKey.value,
            environment = request.environmentKey,
        )

        // The contract the release holds, not the working copy's: a caller generating from a release
        // is validated against what that release promises.
        val dataModel = inputs.dataModel
        val effectiveData = if (dataModel != null) schemaValidator.applyDefaults(dataModel, request.data) else request.data
        if (dataModel != null) {
            val errors = schemaValidator.validate(dataModel, effectiveData)
            if (errors.isNotEmpty()) {
                throw IllegalArgumentException("Data validation failed: ${errors.joinToString("; ") { "${it.path}: ${it.message}" }}")
            }
        }

        @Suppress("UNCHECKED_CAST")
        val dataMap = objectMapper.convertValue(effectiveData, Map::class.java) as Map<String, Any?>
        val outputStream = ByteArrayOutputStream()
        generationService.renderPdfFromRelease(
            inputs = inputs,
            data = dataMap,
            outputStream = outputStream,
            metadata = PdfMetadata(title = inputs.templateName, author = tenant.name),
            culture = localeResolver.resolveCulture(tenant, inputs.variantAttributes),
        )

        val pdfBytes = outputStream.toByteArray()
        val maxSizeBytes = maxDocumentSizeMb * 1024 * 1024
        if (pdfBytes.size > maxSizeBytes) {
            throw IllegalStateException("Generated document size (${pdfBytes.size} bytes) exceeds maximum ($maxSizeBytes bytes)")
        }

        val document = Document(
            id = DocumentKey.generate(),
            tenantKey = request.tenantKey,
            catalogKey = request.catalogKey,
            templateKey = request.templateKey,
            variantKey = request.variantKey,
            versionKey = null,
            releaseVersion = releaseVersion,
            templateResourceId = inputs.templateResourceId,
            filename = request.filename ?: "document-${request.id.value}.pdf",
            correlationId = request.correlationId,
            contentType = "application/pdf",
            sizeBytes = pdfBytes.size.toLong(),
            createdAt = EpistolaClock.offsetDateTime(),
            createdBy = currentUserIdOrNull(),
        )
        return Triple(document, pdfBytes, "release")
    }

    /**
     * Renders a request bound to a draft its environment follows (an override): the draft as it is
     * now, live, with the preview watermark ([app.epistola.suite.generation.DraftDocumentRenderer],
     * which holds no database connection while it lays out the document). Its data is validated
     * against the draft's contract. A draft discarded since the request was accepted fails it.
     */
    private fun generateFromDraft(request: DocumentGenerationRequest, draftId: String): Triple<Document, ByteArray, String> {
        val rendered = draftRenderer.render(request.tenantKey, request.catalogKey, request.templateKey, request.variantKey, request.data, draftId)
        val pdfBytes = rendered.pdf
        val maxSizeBytes = maxDocumentSizeMb * 1024 * 1024
        if (pdfBytes.size > maxSizeBytes) {
            throw IllegalStateException("Generated document size (${pdfBytes.size} bytes) exceeds maximum ($maxSizeBytes bytes)")
        }
        val templateResourceId = rendered.templateResourceId
        val document = Document(
            id = DocumentKey.generate(),
            tenantKey = request.tenantKey,
            catalogKey = request.catalogKey,
            templateKey = request.templateKey,
            variantKey = request.variantKey,
            versionKey = null,
            draftId = draftId,
            draftLabel = request.draftLabel,
            templateResourceId = templateResourceId,
            filename = request.filename ?: "document-${request.id.value}.pdf",
            correlationId = request.correlationId,
            contentType = "application/pdf",
            sizeBytes = pdfBytes.size.toLong(),
            createdAt = EpistolaClock.offsetDateTime(),
            createdBy = currentUserIdOrNull(),
        )
        return Triple(document, pdfBytes, "draft")
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
                    id, tenant_key, catalog_key, registry, namespace, template_key, variant_key, version_key, release_version, draft_id, draft_label,
                    template_resource_id, filename, correlation_id, content_type, size_bytes,
                    created_at, created_by
                )
                VALUES (
                    :id, :tenantId, :catalogKey,
                    (SELECT registry FROM catalogs WHERE tenant_key = :tenantId AND catalog_key = :catalogKey),
                    (SELECT namespace FROM catalogs WHERE tenant_key = :tenantId AND catalog_key = :catalogKey),
                    :templateId, :variantId, :versionId, :releaseVersion, :draftId, :draftLabel,
                    :templateResourceId, :filename, :correlationId, :contentType, :sizeBytes,
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
                .bind("releaseVersion", document.releaseVersion)
                .bind("draftId", document.draftId)
                .bind("draftLabel", document.draftLabel)
                .bind("templateResourceId", document.templateResourceId)
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
                    completedAt = EpistolaClock.offsetDateTime(),
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

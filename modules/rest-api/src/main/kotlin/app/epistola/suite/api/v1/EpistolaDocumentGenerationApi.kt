package app.epistola.suite.api.v1

import app.epistola.api.GenerationApi
import app.epistola.api.model.CollectRequest
import app.epistola.api.model.DocumentListResponse
import app.epistola.api.model.GenerateBatchRequest
import app.epistola.api.model.GenerateDocumentRequest
import app.epistola.api.model.GenerationJobDetail
import app.epistola.api.model.GenerationJobListResponse
import app.epistola.api.model.GenerationJobResponse
import app.epistola.api.model.PreviewDocumentRequest
import app.epistola.suite.api.v1.shared.Pagination
import app.epistola.suite.api.v1.shared.toCommand
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toJobDto
import app.epistola.suite.api.v1.shared.toJobResponse
import app.epistola.suite.api.v1.shared.toQuery
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.GenerationRequestKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.DocumentNotFoundException
import app.epistola.suite.documents.GenerationJobNotFoundException
import app.epistola.suite.documents.commands.CancelGenerationJob
import app.epistola.suite.documents.commands.DeleteDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.CountDocuments
import app.epistola.suite.documents.queries.CountGenerationJobs
import app.epistola.suite.documents.queries.GetDocumentMetadata
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.ListDocuments
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.TenantAccessDeniedException
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.DocumentContentStore
import app.epistola.suite.validation.ValidationException
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.UUID

@RestController
@RequestMapping("/api")
class EpistolaDocumentGenerationApi(
    private val objectMapper: ObjectMapper,
    private val contentStore: DocumentContentStore,
    private val ndjsonResultStream: app.epistola.suite.generation.collect.ndjson.NdjsonResultStream,
) : GenerationApi {

    // ================== Document Preview ==================

    override fun previewDocument(
        tenantId: String,
        previewDocumentRequest: PreviewDocumentRequest,
    ): ResponseEntity<Resource> {
        val pdfBytes = previewDocumentRequest.toQuery(tenantId).query()

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(pdfBytes.size.toLong())
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
            .body(InputStreamResource(ByteArrayInputStream(pdfBytes)))
    }

    // ================== Document Generation ==================

    override fun generateDocument(
        tenantId: String,
        generateDocumentRequest: GenerateDocumentRequest,
    ): ResponseEntity<GenerationJobResponse> {
        val command = generateDocumentRequest.toCommand(tenantId, objectMapper)
        val request = command.execute()

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(request.toJobResponse())
    }

    override fun generateDocumentBatch(
        tenantId: String,
        generateBatchRequest: GenerateBatchRequest,
    ): ResponseEntity<GenerationJobResponse> {
        val command = generateBatchRequest.toCommand(tenantId, objectMapper)
        val request = command.execute()

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(request.toJobResponse())
    }

    // ================== Job Management ==================

    override fun listGenerationJobs(
        tenantId: String,
        status: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<GenerationJobListResponse> {
        val statusEnum = status?.let { RequestStatus.valueOf(it) }
        val typedTenantId = TenantKey.of(tenantId)
        val jobs = ListGenerationJobs(
            tenantId = typedTenantId,
            status = statusEnum,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
        ).query()
        val total = CountGenerationJobs(tenantId = typedTenantId, status = statusEnum).query()

        return ResponseEntity.ok(
            GenerationJobListResponse(
                items = jobs.map { it.toJobDto() },
                page = Pagination.pageMeta(page, size, total),
            ),
        )
    }

    override fun getGenerationJobStatus(
        tenantId: String,
        requestId: UUID,
    ): ResponseEntity<GenerationJobDetail> {
        val typedTenantId = TenantKey.of(tenantId)
        val typedRequestId = GenerationRequestKey.of(requestId)
        val jobResult = GetGenerationJob(typedTenantId, typedRequestId).query()
            ?: throw GenerationJobNotFoundException(typedTenantId, typedRequestId)

        return ResponseEntity.ok(jobResult.toDto(objectMapper))
    }

    override fun cancelGenerationJob(
        tenantId: String,
        requestId: UUID,
    ): ResponseEntity<Unit> {
        val typedTenantId = TenantKey.of(tenantId)
        CancelGenerationJob(typedTenantId, GenerationRequestKey.of(requestId)).execute()

        return ResponseEntity.noContent().build()
    }

    // ================== Document Download ==================

    override fun downloadDocument(
        tenantId: String,
        documentId: UUID,
    ): ResponseEntity<Resource> {
        val tid = TenantKey.of(tenantId)
        val did = DocumentKey.of(documentId)

        val metadata = GetDocumentMetadata(tid, did).query()
            ?: throw DocumentNotFoundException(tid, did)

        val stored = contentStore.get(ContentKey.document(tid, did))
            ?: throw DocumentNotFoundException(tid, did)

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(metadata.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${metadata.filename}\"")
            .body(InputStreamResource(stored.content))
    }

    override fun deleteDocument(
        tenantId: String,
        documentId: UUID,
    ): ResponseEntity<Unit> {
        val typedTenantId = TenantKey.of(tenantId)
        val typedDocumentId = DocumentKey.of(documentId)
        val deleted = DeleteDocument(typedTenantId, typedDocumentId).execute()

        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            throw DocumentNotFoundException(typedTenantId, typedDocumentId)
        }
    }

    // ================== Generation Result Collection (v0.3) ==================

    /**
     * Stream pending generation results to the caller as NDJSON. Thin
     * orchestration around the commands/queries from
     * `app.epistola.suite.generation.collect`:
     *
     *   1. Resolve `consumerId` from the authenticated principal (api-key id)
     *      and `nodeId` from the `X-EP-Node-Id` request header.
     *   2. [TouchConsumerNode] — bumps `last_seen_at` and computes the
     *      caller's current partition assignment (consistent hash ring).
     *   3. [AcknowledgeGenerationResults] — when the request body carries
     *      `acknowledgeUpTo`, advances the per-(consumer, partition) cursor.
     *   4. [FetchGenerationResults] — reads the next page using the
     *      now-advanced cursors.
     *   5. [NdjsonResultStream] — writes the rows + `_meta` line directly
     *      to the response output stream, gzip-encoded by default.
     *
     * Returns `ResponseEntity.ok()` with no body — the body has already been
     * written to the response by the time we return.
     */
    override fun collectGenerationResults(
        tenantId: String,
        acceptEncoding: String,
        collectRequest: CollectRequest?,
    ): ResponseEntity<String> {
        val tenantKey = TenantKey.of(tenantId)
        val principal = app.epistola.suite.security.SecurityContext.current()
        if (principal.currentTenantId != null && principal.currentTenantId != tenantKey) {
            // Defense-in-depth: the api-key auth filter sets currentTenantId from
            // api_keys.tenantKey. The path tenant must match.
            throw TenantAccessDeniedException(tenantKey, principal.email)
        }
        val consumerId = principal.userId.value.toString()
        val nodeId = currentRequest()?.getHeader(HEADER_NODE_ID)?.takeIf { it.isNotBlank() }
            ?: throw ValidationException(field = HEADER_NODE_ID, message = "Header $HEADER_NODE_ID is required")

        // Touch returns this node's PartitionAssignment from the consistent hash ring.
        val assignment = app.epistola.suite.generation.collect.commands.TouchConsumerNode(
            tenantId = tenantKey,
            consumerId = consumerId,
            nodeId = nodeId,
        ).execute()
        val partitions: Set<Int> = assignment.mine.toSet()

        // Apply ack cursor advance, if present.
        val ackUpTo = collectRequest?.acknowledgeUpTo
        if (ackUpTo != null && ackUpTo > 0L && partitions.isNotEmpty()) {
            app.epistola.suite.generation.collect.commands.AcknowledgeGenerationResults(
                tenantId = tenantKey,
                consumerId = consumerId,
                partitions = partitions,
                acknowledgeUpTo = ackUpTo,
            ).execute()
        }

        // Fetch the next page (limit clamped to FetchGenerationResults.MAX_LIMIT).
        val limit = (collectRequest?.limit ?: DEFAULT_COLLECT_LIMIT)
            .coerceIn(1, app.epistola.suite.generation.collect.queries.FetchGenerationResults.MAX_LIMIT)
        val page = if (partitions.isEmpty()) {
            // No partitions assigned — nothing to fetch, but still emit a meta
            // line so the client knows its (empty) assignment.
            app.epistola.suite.generation.collect.queries.FetchResultsPage(emptyList(), hasMore = false, lastSequence = null)
        } else {
            app.epistola.suite.generation.collect.queries.FetchGenerationResults(
                tenantId = tenantKey,
                consumerId = consumerId,
                partitions = partitions,
                limit = limit,
            ).query()
        }

        val encoding = ndjsonResultStream.negotiateEncoding(acceptEncoding)
        val response = currentResponse()
            ?: throw IllegalStateException("Current HTTP response is not available")
        response.status = HttpStatus.OK.value()
        response.contentType = NDJSON_CONTENT_TYPE
        if (encoding == app.epistola.suite.generation.collect.ndjson.NdjsonResultStream.Encoding.GZIP) {
            response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip")
        }

        val meta = app.epistola.suite.generation.collect.ndjson.NdjsonResultStream.MetaLine(
            hasMore = page.hasMore,
            count = page.rows.size,
            lastSequence = page.lastSequence,
            partitions = assignment,
        )
        ndjsonResultStream.writeTo(response.outputStream, page.rows, meta, encoding)
        // Body is already on the wire — return null body, the framework won't
        // serialize anything because we've already committed the response.
        return ResponseEntity.status(HttpStatus.OK).build()
    }

    private fun currentRequest(): jakarta.servlet.http.HttpServletRequest? = (
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            as? org.springframework.web.context.request.ServletRequestAttributes
        )?.request

    private fun currentResponse(): jakarta.servlet.http.HttpServletResponse? = (
        org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
            as? org.springframework.web.context.request.ServletRequestAttributes
        )?.response

    private companion object {
        const val HEADER_NODE_ID = "X-EP-Node-Id"
        const val NDJSON_CONTENT_TYPE = "application/vnd.epistola.v1+ndjson"
        const val DEFAULT_COLLECT_LIMIT = 100
    }

    // ================== Document Listing ==================

    override fun listDocuments(
        tenantId: String,
        templateId: String?,
        correlationId: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<DocumentListResponse> {
        val typedTenantId = TenantKey.of(tenantId)
        val typedTemplateId = templateId?.let { TemplateKey.of(it) }
        val documents = ListDocuments(
            tenantId = typedTenantId,
            templateId = typedTemplateId,
            correlationId = correlationId,
            limit = Pagination.limitOf(size),
            offset = Pagination.offsetOf(page, size),
        ).query()
        val total = CountDocuments(
            tenantId = typedTenantId,
            templateId = typedTemplateId,
            correlationId = correlationId,
        ).query()

        return ResponseEntity.ok(
            DocumentListResponse(
                items = documents.map { it.toDto() },
                page = Pagination.pageMeta(page, size, total),
            ),
        )
    }
}

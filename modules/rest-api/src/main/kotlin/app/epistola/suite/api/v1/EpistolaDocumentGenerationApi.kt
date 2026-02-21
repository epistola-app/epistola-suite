package app.epistola.suite.api.v1

import app.epistola.api.GenerationApi
import app.epistola.api.model.DocumentListResponse
import app.epistola.api.model.GenerateBatchRequest
import app.epistola.api.model.GenerateDocumentRequest
import app.epistola.api.model.GenerationJobDetail
import app.epistola.api.model.GenerationJobListResponse
import app.epistola.api.model.GenerationJobResponse
import app.epistola.suite.api.v1.shared.toCommand
import app.epistola.suite.api.v1.shared.toDto
import app.epistola.suite.api.v1.shared.toJobDto
import app.epistola.suite.api.v1.shared.toJobResponse
import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.documents.commands.CancelGenerationJob
import app.epistola.suite.documents.commands.DeleteDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetDocumentMetadata
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.ListDocuments
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.storage.ContentKey
import app.epistola.suite.storage.ContentStore
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@RestController
class EpistolaDocumentGenerationApi(
    private val objectMapper: ObjectMapper,
    private val contentStore: ContentStore,
) : GenerationApi {

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
        val jobs = ListGenerationJobs(
            tenantId = TenantId.of(tenantId),
            status = statusEnum,
            limit = size,
            offset = page * size,
        ).query()

        // TODO: Get total count for pagination
        val response = GenerationJobListResponse(
            items = jobs.map { it.toJobDto() },
            page = page,
            propertySize = size,
            totalElements = jobs.size,
            totalPages = 1,
        )

        return ResponseEntity.ok(response)
    }

    override fun getGenerationJobStatus(
        tenantId: String,
        requestId: UUID,
    ): ResponseEntity<GenerationJobDetail> {
        val jobResult = GetGenerationJob(TenantId.of(tenantId), GenerationRequestId.of(requestId)).query()
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(jobResult.toDto(objectMapper))
    }

    override fun cancelGenerationJob(
        tenantId: String,
        requestId: UUID,
    ): ResponseEntity<Unit> {
        val cancelled = CancelGenerationJob(TenantId.of(tenantId), GenerationRequestId.of(requestId)).execute()

        return if (cancelled) {
            ResponseEntity.noContent().build()
        } else {
            // Job not found or cannot be cancelled
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    // ================== Document Download ==================

    override fun downloadDocument(
        tenantId: String,
        documentId: UUID,
    ): ResponseEntity<Resource> {
        val tid = TenantId.of(tenantId)
        val did = DocumentId.of(documentId)

        val metadata = GetDocumentMetadata(tid, did).query()
            ?: return ResponseEntity.notFound().build()

        val stored = contentStore.get(ContentKey.document(tid, did))
            ?: return ResponseEntity.notFound().build()

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
        val deleted = DeleteDocument(TenantId.of(tenantId), DocumentId.of(documentId)).execute()

        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Document Listing ==================

    override fun listDocuments(
        tenantId: String,
        templateId: String?,
        correlationId: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<DocumentListResponse> {
        val documents = ListDocuments(
            tenantId = TenantId.of(tenantId),
            templateId = templateId?.let { TemplateId.of(it) },
            correlationId = correlationId,
            limit = size,
            offset = page * size,
        ).query()

        // TODO: Get total count for pagination
        val response = DocumentListResponse(
            items = documents.map { it.toDto() },
            page = page,
            propertySize = size,
            totalElements = documents.size,
            totalPages = 1,
        )

        return ResponseEntity.ok(response)
    }
}

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
import app.epistola.suite.documents.commands.CancelGenerationJob
import app.epistola.suite.documents.commands.DeleteDocument
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.documents.queries.GetDocument
import app.epistola.suite.documents.queries.GetGenerationJob
import app.epistola.suite.documents.queries.ListDocuments
import app.epistola.suite.documents.queries.ListGenerationJobs
import app.epistola.suite.mediator.Mediator
import org.springframework.core.io.ByteArrayResource
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
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) : GenerationApi {

    // ================== Document Generation ==================

    override fun generateDocument(
        tenantId: UUID,
        generateDocumentRequest: GenerateDocumentRequest,
    ): ResponseEntity<GenerationJobResponse> {
        val command = generateDocumentRequest.toCommand(tenantId, objectMapper)
        val request = mediator.send(command)

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(request.toJobResponse())
    }

    override fun generateDocumentBatch(
        tenantId: UUID,
        generateBatchRequest: GenerateBatchRequest,
    ): ResponseEntity<GenerationJobResponse> {
        val command = generateBatchRequest.toCommand(tenantId, objectMapper)
        val request = mediator.send(command)

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(request.toJobResponse())
    }

    // ================== Job Management ==================

    override fun listGenerationJobs(
        tenantId: UUID,
        status: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<GenerationJobListResponse> {
        val statusEnum = status?.let { RequestStatus.valueOf(it) }
        val jobs = mediator.query(
            ListGenerationJobs(
                tenantId = tenantId,
                status = statusEnum,
                limit = size,
                offset = page * size,
            ),
        )

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
        tenantId: UUID,
        requestId: UUID,
    ): ResponseEntity<GenerationJobDetail> {
        val jobResult = mediator.query(GetGenerationJob(tenantId, requestId))
            ?: return ResponseEntity.notFound().build()

        return ResponseEntity.ok(jobResult.toDto(objectMapper))
    }

    override fun cancelGenerationJob(
        tenantId: UUID,
        requestId: UUID,
    ): ResponseEntity<Unit> {
        val cancelled = mediator.send(CancelGenerationJob(tenantId, requestId))

        return if (cancelled) {
            ResponseEntity.noContent().build()
        } else {
            // Job not found or cannot be cancelled
            ResponseEntity.status(HttpStatus.CONFLICT).build()
        }
    }

    // ================== Document Download ==================

    override fun downloadDocument(
        tenantId: UUID,
        documentId: UUID,
    ): ResponseEntity<Resource> {
        val document = mediator.query(GetDocument(tenantId, documentId))
            ?: return ResponseEntity.notFound().build()

        val resource = ByteArrayResource(document.content)

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .contentLength(document.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${document.filename}\"")
            .body(resource)
    }

    override fun deleteDocument(
        tenantId: UUID,
        documentId: UUID,
    ): ResponseEntity<Unit> {
        val deleted = mediator.send(DeleteDocument(tenantId, documentId))

        return if (deleted) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }
    }

    // ================== Document Listing ==================

    override fun listDocuments(
        tenantId: UUID,
        templateId: UUID?,
        correlationId: String?,
        page: Int,
        size: Int,
    ): ResponseEntity<DocumentListResponse> {
        val documents = mediator.query(
            ListDocuments(
                tenantId = tenantId,
                templateId = templateId,
                correlationId = correlationId,
                limit = size,
                offset = page * size,
            ),
        )

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

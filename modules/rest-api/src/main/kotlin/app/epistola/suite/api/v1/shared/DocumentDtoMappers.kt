package app.epistola.suite.api.v1.shared

import app.epistola.api.model.DocumentDto
import app.epistola.api.model.DocumentGenerationItemDto
import app.epistola.api.model.DocumentGenerationJobDto
import app.epistola.api.model.GenerateBatchRequest
import app.epistola.api.model.GenerateDocumentRequest
import app.epistola.api.model.GenerationJobDetail
import app.epistola.api.model.GenerationJobResponse
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.queries.DocumentMetadata
import app.epistola.suite.documents.queries.GenerationJobResult
import tools.jackson.databind.ObjectMapper

/**
 * Extension functions for mapping between domain models and API DTOs.
 */

// ==================== Document ====================

internal fun DocumentMetadata.toDto() = DocumentDto(
    id = id.value,
    tenantId = tenantId.value,
    templateId = templateId.value,
    variantId = variantId.value,
    versionId = versionId.value,
    filename = filename,
    correlationId = correlationId,
    contentType = contentType,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    createdBy = createdBy?.value?.toString(),
)

// ==================== Document Generation Request ====================

internal fun DocumentGenerationRequest.toJobDto() = DocumentGenerationJobDto(
    id = id.value,
    jobType = DocumentGenerationJobDto.JobType.SINGLE, // Always SINGLE in flattened structure
    status = DocumentGenerationJobDto.Status.valueOf(status.name),
    totalCount = 1, // Always 1 in flattened structure
    completedCount = if (status == app.epistola.suite.documents.model.RequestStatus.COMPLETED) 1 else 0,
    failedCount = if (status == app.epistola.suite.documents.model.RequestStatus.FAILED) 1 else 0,
    errorMessage = errorMessage,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
    progressPercentage = if (isTerminal) 100.0 else 0.0,
)

internal fun DocumentGenerationRequest.toJobResponse() = GenerationJobResponse(
    requestId = id.value,
    status = GenerationJobResponse.Status.valueOf(status.name),
    jobType = GenerationJobResponse.JobType.SINGLE, // Always SINGLE in flattened structure
    totalCount = 1, // Always 1 in flattened structure
    createdAt = createdAt,
)

// ==================== Document Generation Item ====================
// NOTE: In the flattened structure, each request IS an item. Mapping a request to an ItemDto for API compatibility.

internal fun DocumentGenerationRequest.toItemDto(objectMapper: ObjectMapper) = DocumentGenerationItemDto(
    id = id.value,
    templateId = templateId.value,
    variantId = variantId.value,
    versionId = versionId?.value,
    environmentId = environmentId?.value,
    data = objectMapper.valueToTree(data),
    filename = filename,
    correlationId = correlationId,
    status = DocumentGenerationItemDto.Status.valueOf(status.name),
    errorMessage = errorMessage,
    documentId = documentId?.value,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
)

// ==================== Generation Job Result ====================

internal fun GenerationJobResult.toDto(objectMapper: ObjectMapper) = GenerationJobDetail(
    request = request.toJobDto(),
    items = items.map { it.toItemDto(objectMapper) },
)

// ==================== Request DTOs to Commands ====================

internal fun GenerateDocumentRequest.toCommand(
    tenantId: String,
    objectMapper: ObjectMapper,
) = app.epistola.suite.documents.commands.GenerateDocument(
    tenantId = TenantId.of(tenantId),
    templateId = TemplateId.of(templateId),
    variantId = VariantId.of(requireNotNull(variantId) { "variantId is required" }),
    versionId = versionId?.let { VersionId.of(it) },
    environmentId = environmentId?.let { EnvironmentId.of(it) },
    data = data,
    filename = filename,
    correlationId = correlationId,
)

internal fun app.epistola.api.model.BatchGenerationItem.toBatchItem(
    objectMapper: ObjectMapper,
) = app.epistola.suite.documents.commands.BatchGenerationItem(
    templateId = TemplateId.of(templateId),
    variantId = VariantId.of(requireNotNull(variantId) { "variantId is required" }),
    versionId = versionId?.let { VersionId.of(it) },
    environmentId = environmentId?.let { EnvironmentId.of(it) },
    data = data,
    filename = filename,
    correlationId = correlationId,
)

internal fun GenerateBatchRequest.toCommand(
    tenantId: String,
    objectMapper: ObjectMapper,
) = app.epistola.suite.documents.commands.GenerateDocumentBatch(
    tenantId = TenantId.of(tenantId),
    items = items.map { it.toBatchItem(objectMapper) },
)

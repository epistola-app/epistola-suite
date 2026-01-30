package app.epistola.suite.api.v1.shared

import app.epistola.api.model.*
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.model.*
import app.epistola.suite.documents.queries.DocumentMetadata
import app.epistola.suite.documents.queries.GenerationJobResult
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * Extension functions for mapping between domain models and API DTOs.
 */

// ==================== Document ====================

internal fun DocumentMetadata.toDto() = DocumentDto(
    id = id,
    tenantId = tenantId,
    templateId = templateId,
    variantId = variantId,
    versionId = versionId,
    filename = filename,
    contentType = contentType,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
    createdBy = createdBy
)

// ==================== Document Generation Request ====================

internal fun DocumentGenerationRequest.toJobDto() = DocumentGenerationJobDto(
    id = id,
    jobType = DocumentGenerationJobDto.JobType.valueOf(jobType.name),
    status = DocumentGenerationJobDto.Status.valueOf(status.name),
    totalCount = totalCount,
    completedCount = completedCount,
    failedCount = failedCount,
    errorMessage = errorMessage,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
    progressPercentage = progressPercentage
)

internal fun DocumentGenerationRequest.toJobResponse() = GenerationJobResponse(
    requestId = id,
    status = GenerationJobResponse.Status.valueOf(status.name),
    jobType = GenerationJobResponse.JobType.valueOf(jobType.name),
    totalCount = totalCount,
    createdAt = createdAt
)

// ==================== Document Generation Item ====================

internal fun DocumentGenerationItem.toDto(objectMapper: ObjectMapper) = DocumentGenerationItemDto(
    id = id,
    templateId = templateId,
    variantId = variantId,
    versionId = versionId,
    environmentId = environmentId,
    data = objectMapper.convertValue(data, Map::class.java) as Map<String, Any>,
    filename = filename,
    status = DocumentGenerationItemDto.Status.valueOf(status.name),
    errorMessage = errorMessage,
    documentId = documentId,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt
)

// ==================== Generation Job Result ====================

internal fun GenerationJobResult.toDto(objectMapper: ObjectMapper) = GenerationJobDetail(
    request = request.toJobDto(),
    items = items.map { it.toDto(objectMapper) }
)

// ==================== Request DTOs to Commands ====================

internal fun GenerateDocumentRequest.toCommand(
    tenantId: Long,
    objectMapper: ObjectMapper
) = app.epistola.suite.documents.commands.GenerateDocument(
    tenantId = tenantId,
    templateId = templateId,
    variantId = variantId,
    versionId = versionId,
    environmentId = environmentId,
    data = objectMapper.valueToTree(data),
    filename = filename
)

internal fun app.epistola.api.model.BatchGenerationItem.toBatchItem(
    objectMapper: ObjectMapper
) = app.epistola.suite.documents.commands.BatchGenerationItem(
    templateId = templateId,
    variantId = variantId,
    versionId = versionId,
    environmentId = environmentId,
    data = objectMapper.valueToTree<ObjectNode>(data),
    filename = filename
)

internal fun GenerateBatchRequest.toCommand(
    tenantId: Long,
    objectMapper: ObjectMapper
) = app.epistola.suite.documents.commands.GenerateDocumentBatch(
    tenantId = tenantId,
    items = items.map { it.toBatchItem(objectMapper) }
)

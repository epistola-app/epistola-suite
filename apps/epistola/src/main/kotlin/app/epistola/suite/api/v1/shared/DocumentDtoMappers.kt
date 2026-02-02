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
import app.epistola.suite.documents.model.DocumentGenerationItem
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.queries.DocumentMetadata
import app.epistola.suite.documents.queries.GenerationJobResult
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

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
    createdBy = createdBy,
)

// ==================== Document Generation Request ====================

internal fun DocumentGenerationRequest.toJobDto() = DocumentGenerationJobDto(
    id = id.value,
    jobType = DocumentGenerationJobDto.JobType.valueOf(jobType.name),
    status = DocumentGenerationJobDto.Status.valueOf(status.name),
    totalCount = totalCount,
    completedCount = completedCount,
    failedCount = failedCount,
    errorMessage = errorMessage,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
    progressPercentage = progressPercentage,
)

internal fun DocumentGenerationRequest.toJobResponse() = GenerationJobResponse(
    requestId = id.value,
    status = GenerationJobResponse.Status.valueOf(status.name),
    jobType = GenerationJobResponse.JobType.valueOf(jobType.name),
    totalCount = totalCount,
    createdAt = createdAt,
)

// ==================== Document Generation Item ====================

internal fun DocumentGenerationItem.toDto(objectMapper: ObjectMapper) = DocumentGenerationItemDto(
    id = id.value,
    templateId = templateId.value,
    variantId = variantId.value,
    versionId = versionId?.value,
    environmentId = environmentId?.value,
    data = objectMapper.convertValue(data, Map::class.java) as Map<String, Any>,
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
    items = items.map { it.toDto(objectMapper) },
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
    data = objectMapper.valueToTree(data),
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
    data = objectMapper.valueToTree<ObjectNode>(data),
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

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.BatchDownloadInfo
import app.epistola.api.model.BatchDownloadPartInfo
import app.epistola.api.model.DocumentDto
import app.epistola.api.model.DocumentGenerationItemDto
import app.epistola.api.model.DocumentGenerationJobDto
import app.epistola.api.model.GenerateBatchRequest
import app.epistola.api.model.GenerateDocumentRequest
import app.epistola.api.model.GenerationJobDetail
import app.epistola.api.model.GenerationJobResponse
import app.epistola.api.model.VariantSelectionAttribute
import app.epistola.suite.common.ids.BatchKey
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.model.AssemblyStatus
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.queries.DocumentMetadata
import app.epistola.suite.documents.queries.GenerationJobResult
import app.epistola.suite.templates.services.VariantSelectionCriteria
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
    assemblyStatus = app.epistola.api.model.AssemblyStatus.NONE,
)

internal fun DocumentGenerationRequest.toJobResponse() = GenerationJobResponse(
    requestId = id.value,
    status = GenerationJobResponse.Status.valueOf(status.name),
    jobType = GenerationJobResponse.JobType.SINGLE, // Always SINGLE in flattened structure
    totalCount = 1, // Always 1 in flattened structure
    createdAt = createdAt,
)

internal fun BatchKey.toJobResponse() = GenerationJobResponse(
    requestId = value, // Use batch ID as request ID for API compatibility
    status = GenerationJobResponse.Status.PENDING,
    jobType = GenerationJobResponse.JobType.BATCH,
    totalCount = 0, // Count not available without querying - caller should use batch endpoints
    createdAt = java.time.OffsetDateTime.now(),
)

// ==================== Document Generation Item ====================
// NOTE: In the flattened structure, each request IS an item. Mapping a request to an ItemDto for API compatibility.

internal fun DocumentGenerationRequest.toItemDto(objectMapper: ObjectMapper) = DocumentGenerationItemDto(
    id = id.value,
    templateId = templateKey.value,
    variantId = variantKey.value,
    versionId = versionKey?.value,
    environmentId = environmentKey?.value,
    data = objectMapper.valueToTree(data),
    filename = filename,
    correlationId = correlationKey,
    status = DocumentGenerationItemDto.Status.valueOf(status.name),
    errorMessage = errorMessage,
    documentId = documentKey?.value,
    createdAt = createdAt,
    startedAt = startedAt,
    completedAt = completedAt,
)

// ==================== Generation Job Result ====================

internal fun GenerationJobResult.toDto(objectMapper: ObjectMapper): GenerationJobDetail {
    val batchSnapshot = batch
    val jobDto = if (batchSnapshot != null) {
        // Batch job: use batch-level assembly status
        request.toJobDto().copy(
            assemblyStatus = app.epistola.api.model.AssemblyStatus.valueOf(batchSnapshot.assemblyStatus.name),
        )
    } else {
        request.toJobDto()
    }

    val downloads = if (batchSnapshot != null && batchSnapshot.assemblyStatus == AssemblyStatus.COMPLETED) {
        batchSnapshot.downloadParts.map { (format, parts) ->
            BatchDownloadInfo(
                format = app.epistola.api.model.BatchDownloadFormat.valueOf(format),
                parts = parts.map { part ->
                    BatchDownloadPartInfo(
                        partNumber = part.partNumber,
                        sizeBytes = part.sizeBytes,
                    )
                },
            )
        }
    } else {
        null
    }

    return GenerationJobDetail(
        request = jobDto,
        items = items.map { it.toItemDto(objectMapper) },
        downloads = downloads,
    )
}

// ==================== Request DTOs to Commands ====================

internal fun GenerateDocumentRequest.toCommand(
    tenantId: String,
    objectMapper: ObjectMapper,
): app.epistola.suite.documents.commands.GenerateDocument {
    require(variantId == null || attributes == null) {
        "Cannot specify both variantId and attributes"
    }
    return app.epistola.suite.documents.commands.GenerateDocument(
        tenantId = TenantKey.of(tenantId),
        templateId = TemplateKey.of(templateId),
        variantId = variantId?.let { VariantKey.of(it) },
        variantSelectionCriteria = attributes?.toSelectionCriteria(),
        versionId = versionId?.let { VersionKey.of(it) },
        environmentId = environmentId?.let { EnvironmentKey.of(it) },
        data = data,
        filename = filename,
        correlationId = correlationId,
    )
}

internal fun app.epistola.api.model.BatchGenerationItem.toBatchItem(
    objectMapper: ObjectMapper,
): app.epistola.suite.documents.commands.BatchGenerationItem {
    require(variantId == null || attributes == null) {
        "Cannot specify both variantId and attributes"
    }
    return app.epistola.suite.documents.commands.BatchGenerationItem(
        templateId = TemplateKey.of(templateId),
        variantId = variantId?.let { VariantKey.of(it) },
        variantSelectionCriteria = attributes?.toSelectionCriteria(),
        versionId = versionId?.let { VersionKey.of(it) },
        environmentId = environmentId?.let { EnvironmentKey.of(it) },
        data = data,
        filename = filename,
        correlationId = correlationId,
    )
}

private fun List<VariantSelectionAttribute>.toSelectionCriteria(): VariantSelectionCriteria {
    val required = mutableMapOf<String, String>()
    val optional = mutableMapOf<String, String>()
    for (attr in this) {
        if (attr.required != false) {
            required[attr.key] = attr.value
        } else {
            optional[attr.key] = attr.value
        }
    }
    return VariantSelectionCriteria(
        requiredAttributes = required,
        optionalAttributes = optional,
    )
}

internal fun GenerateBatchRequest.toCommand(
    tenantId: String,
    objectMapper: ObjectMapper,
) = app.epistola.suite.documents.commands.GenerateDocumentBatch(
    tenantId = TenantKey.of(tenantId),
    items = items.map { it.toBatchItem(objectMapper) },
    downloadFormats = downloadFormats?.map { it.toDomain() } ?: emptyList(),
)

private fun app.epistola.api.model.BatchDownloadFormat.toDomain(): app.epistola.suite.documents.model.BatchDownloadFormat = when (this) {
    app.epistola.api.model.BatchDownloadFormat.ZIP -> app.epistola.suite.documents.model.BatchDownloadFormat.ZIP
    app.epistola.api.model.BatchDownloadFormat.MERGED_PDF -> app.epistola.suite.documents.model.BatchDownloadFormat.MERGED_PDF
}

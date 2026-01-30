package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ActivationDto
import app.epistola.api.model.DataExampleDto
import app.epistola.api.model.EnvironmentDto
import app.epistola.api.model.TemplateDto
import app.epistola.api.model.TemplateSummaryDto
import app.epistola.api.model.TenantDto
import app.epistola.api.model.VariantDto
import app.epistola.api.model.VariantSummaryDto
import app.epistola.api.model.VersionDto
import app.epistola.api.model.VersionSummaryDto
import app.epistola.suite.activations.ActivationDetails
import app.epistola.suite.environments.Environment
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.variants.TemplateVariant
import app.epistola.suite.variants.VariantSummary
import app.epistola.suite.versions.TemplateVersion
import app.epistola.suite.versions.VersionStatus
import tools.jackson.databind.ObjectMapper

internal fun Tenant.toDto() = TenantDto(
    id = id,
    name = name,
    createdAt = createdAt,
)

internal fun Environment.toDto() = EnvironmentDto(
    id = id,
    tenantId = tenantId,
    name = name,
    createdAt = createdAt,
)

internal fun DocumentTemplate.toSummaryDto() = TemplateSummaryDto(
    id = id,
    tenantId = tenantId,
    name = name,
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun DocumentTemplate.toDto(objectMapper: ObjectMapper, variantSummaries: List<VariantSummary>) = TemplateDto(
    id = id,
    tenantId = tenantId,
    name = name,
    schema = schema?.let { objectMapper.convertValue(it, Map::class.java) as Map<String, Any> },
    dataModel = dataModel?.let { objectMapper.convertValue(it, Map::class.java) as Map<String, Any> },
    dataExamples = dataExamples.map { example ->
        DataExampleDto(
            id = example.id,
            name = example.name,
            data = objectMapper.convertValue(example.data, Map::class.java) as Map<String, Any>,
        )
    },
    variants = variantSummaries.map { it.toDto() },
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun VariantSummary.toDto() = VariantSummaryDto(
    id = id,
    title = title,
    tags = tags,
    hasDraft = hasDraft,
    publishedVersions = publishedVersions,
)

internal fun TemplateVariant.toDto(info: VariantVersionInfo) = VariantDto(
    id = id,
    templateId = templateId,
    title = title,
    description = description,
    tags = tags,
    hasDraft = info.hasDraft,
    publishedVersions = info.publishedVersions,
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun TemplateVersion.toDto(objectMapper: ObjectMapper) = VersionDto(
    id = id,
    variantId = variantId,
    versionNumber = versionNumber,
    templateModel = templateModel?.let { objectMapper.convertValue(it, Map::class.java) as Map<String, Any> },
    status = status.toDtoStatus(),
    createdAt = createdAt,
    publishedAt = publishedAt,
    archivedAt = archivedAt,
)

internal fun app.epistola.suite.versions.VersionSummary.toSummaryDto() = VersionSummaryDto(
    id = id,
    variantId = variantId,
    versionNumber = versionNumber,
    status = status.toSummaryDtoStatus(),
    createdAt = createdAt,
    publishedAt = publishedAt,
    archivedAt = archivedAt,
)

internal fun VersionStatus.toDtoStatus() = when (this) {
    VersionStatus.DRAFT -> VersionDto.Status.DRAFT
    VersionStatus.PUBLISHED -> VersionDto.Status.PUBLISHED
    VersionStatus.ARCHIVED -> VersionDto.Status.ARCHIVED
}

internal fun VersionStatus.toSummaryDtoStatus() = when (this) {
    VersionStatus.DRAFT -> VersionSummaryDto.Status.DRAFT
    VersionStatus.PUBLISHED -> VersionSummaryDto.Status.PUBLISHED
    VersionStatus.ARCHIVED -> VersionSummaryDto.Status.ARCHIVED
}

internal fun ActivationDetails.toDto() = ActivationDto(
    environmentId = environmentId,
    environmentName = environmentName,
    versionId = versionId,
    versionNumber = versionNumber,
    activatedAt = activatedAt,
)

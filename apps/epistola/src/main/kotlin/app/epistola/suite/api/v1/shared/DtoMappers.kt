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
import app.epistola.suite.environments.Environment
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.model.ActivationDetails
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.model.VersionStatus
import app.epistola.suite.tenants.Tenant
import tools.jackson.databind.ObjectMapper

internal fun Tenant.toDto() = TenantDto(
    id = id.value,
    name = name,
    createdAt = createdAt,
)

internal fun Environment.toDto() = EnvironmentDto(
    id = id.value,
    tenantId = tenantId.value,
    name = name,
    createdAt = createdAt,
)

internal fun DocumentTemplate.toSummaryDto() = TemplateSummaryDto(
    id = id.value,
    tenantId = tenantId.value,
    name = name,
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun DocumentTemplate.toDto(objectMapper: ObjectMapper, variantSummaries: List<VariantSummary>) = TemplateDto(
    id = id.value,
    tenantId = tenantId.value,
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
    id = id.value,
    title = title,
    tags = tags,
    hasDraft = hasDraft,
    publishedVersions = publishedVersions,
)

internal fun TemplateVariant.toDto(info: VariantVersionInfo) = VariantDto(
    id = id.value,
    templateId = templateId.value,
    title = title,
    description = description,
    tags = tags,
    hasDraft = info.hasDraft,
    publishedVersions = info.publishedVersions,
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun TemplateVersion.toDto(objectMapper: ObjectMapper) = VersionDto(
    id = id.value,
    variantId = variantId.value,
    templateModel = templateModel?.let { objectMapper.valueToTree(it) },
    status = status.toDtoStatus(),
    createdAt = createdAt,
    publishedAt = publishedAt,
    archivedAt = archivedAt,
)

internal fun app.epistola.suite.templates.model.VersionSummary.toSummaryDto() = VersionSummaryDto(
    id = id.value,
    variantId = variantId.value,
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
    environmentId = environmentId.value,
    environmentName = environmentName,
    versionId = versionId.value,
    activatedAt = activatedAt,
)

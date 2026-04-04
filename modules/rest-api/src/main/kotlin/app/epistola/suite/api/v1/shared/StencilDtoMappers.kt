package app.epistola.suite.api.v1.shared

import app.epistola.api.model.StencilDto
import app.epistola.api.model.StencilSummaryDto
import app.epistola.api.model.StencilUsageDto
import app.epistola.api.model.StencilVersionDto
import app.epistola.api.model.StencilVersionSummaryDto
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.model.StencilUsage
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.stencils.model.StencilVersionSummary
import tools.jackson.databind.ObjectMapper

internal fun Stencil.toDto(versions: List<StencilVersionSummary>) = StencilDto(
    id = id.value,
    tenantId = tenantKey.value,
    name = name,
    description = description,
    tags = tags.ifEmpty { null },
    versions = versions.map { it.toDto() },
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun Stencil.toSummaryDto(latestPublishedVersion: Int?) = StencilSummaryDto(
    id = id.value,
    tenantId = tenantKey.value,
    name = name,
    description = description,
    tags = tags.ifEmpty { null },
    latestPublishedVersion = latestPublishedVersion,
    createdAt = createdAt,
    lastModified = lastModified,
)

internal fun StencilVersion.toDto(objectMapper: ObjectMapper) = StencilVersionDto(
    id = id.value,
    stencilId = stencilKey.value,
    status = status.toApiStatus(),
    content = objectMapper.valueToTree(content),
    createdAt = createdAt,
    publishedAt = publishedAt,
    archivedAt = archivedAt,
)

internal fun StencilVersionSummary.toDto() = StencilVersionSummaryDto(
    id = id.value,
    status = status.toApiSummaryStatus(),
    createdAt = createdAt,
    publishedAt = publishedAt,
    archivedAt = archivedAt,
)

internal fun StencilUsage.toDto() = StencilUsageDto(
    templateId = templateId.value,
    templateName = templateName,
    variantId = variantId.value,
    versionId = versionId.value,
    stencilVersion = stencilVersion,
)

internal fun StencilVersionStatus.toApiStatus() = when (this) {
    StencilVersionStatus.DRAFT -> StencilVersionDto.Status.DRAFT
    StencilVersionStatus.PUBLISHED -> StencilVersionDto.Status.PUBLISHED
    StencilVersionStatus.ARCHIVED -> StencilVersionDto.Status.ARCHIVED
}

internal fun StencilVersionStatus.toApiSummaryStatus() = when (this) {
    StencilVersionStatus.DRAFT -> StencilVersionSummaryDto.Status.DRAFT
    StencilVersionStatus.PUBLISHED -> StencilVersionSummaryDto.Status.PUBLISHED
    StencilVersionStatus.ARCHIVED -> StencilVersionSummaryDto.Status.ARCHIVED
}

internal fun String.toStencilVersionStatus() = when (this.lowercase()) {
    "draft" -> StencilVersionStatus.DRAFT
    "published" -> StencilVersionStatus.PUBLISHED
    "archived" -> StencilVersionStatus.ARCHIVED
    else -> null
}

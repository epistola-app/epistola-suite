// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1.shared

import app.epistola.api.model.ResourceStatus
import app.epistola.api.model.StencilContentDto
import app.epistola.api.model.StencilDto
import app.epistola.api.model.StencilSummaryDto
import app.epistola.api.model.StencilUsageDto
import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.StencilSummaryWithVersionInfo
import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.stencils.queries.StencilInstance
import tools.jackson.databind.node.ObjectNode

internal fun Stencil.toDto(status: ResourceStatus) = StencilDto(
    slug = id.value,
    tenantId = tenantKey.value,
    name = name,
    description = description,
    tags = tags.ifEmpty { null },
    status = status,
    createdAt = createdAt,
    lastModified = updatedAt,
)

internal fun Stencil.toSummaryDto(status: ResourceStatus) = StencilSummaryDto(
    slug = id.value,
    tenantId = tenantKey.value,
    name = name,
    description = description,
    tags = tags.ifEmpty { null },
    status = status,
    createdAt = createdAt,
    lastModified = updatedAt,
)

internal fun StencilSummaryWithVersionInfo.toSummaryDto(status: ResourceStatus) = StencilSummaryDto(
    slug = id.value,
    tenantId = tenantKey.value,
    name = name,
    description = description,
    tags = tags.ifEmpty { null },
    status = status,
    createdAt = createdAt,
    lastModified = updatedAt,
)

internal fun StencilVersion.toContentDto(status: ResourceStatus) = StencilContentDto(
    stencilId = stencilKey.value,
    content = content,
    parameterSchema = parameterSchema as? ObjectNode,
    status = status,
    lastModified = publishedAt ?: createdAt,
    readyAt = if (status == ResourceStatus.READY) publishedAt else null,
)

internal fun StencilInstance.toUsageDto() = StencilUsageDto(
    templateId = templateKey.value,
    templateName = templateName,
    variantId = variantKey.value,
    heldBack = heldBack,
)

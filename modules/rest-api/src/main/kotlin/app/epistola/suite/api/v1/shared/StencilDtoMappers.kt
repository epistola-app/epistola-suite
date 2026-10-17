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
import app.epistola.suite.stencils.model.StencilUsageDetail
import tools.jackson.databind.node.ObjectNode

internal fun Stencil.toDto(status: ResourceStatus) = StencilDto(
    key = id.value,
    tenantKey = tenantKey.value,
    name = name,
    description = description,
    tags = tags.ifEmpty { null },
    status = status,
    createdAt = createdAt,
    lastModified = updatedAt,
)

internal fun Stencil.toSummaryDto(status: ResourceStatus) = StencilSummaryDto(
    key = id.value,
    tenantKey = tenantKey.value,
    name = name,
    description = description,
    tags = tags.ifEmpty { null },
    status = status,
    createdAt = createdAt,
    lastModified = updatedAt,
)

/** The stencil's working copy. The time it was marked ready is not recorded where this reads it. */
internal fun Stencil.toContentDto(status: ResourceStatus) = StencilContentDto(
    stencilKey = id.value,
    content = content,
    parameterSchema = parameterSchema as? ObjectNode,
    status = status,
    lastModified = updatedAt,
    readyAt = null,
)

/**
 * A variant using the stencil. An instance is held back when the variant's working model no longer
 * fits the stencil's working copy: that is what [StencilUsageDetail.problems] lists.
 */
internal fun StencilUsageDetail.toUsageDto() = StencilUsageDto(
    templateKey = templateId.value,
    templateName = templateName,
    variantKey = variantId.value,
    heldBack = problems.isNotEmpty(),
)

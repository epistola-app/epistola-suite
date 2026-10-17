// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.stencils.Stencil
import app.epistola.suite.stencils.model.StencilUsageDetail
import app.epistola.suite.templates.model.TemplateDocument
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime

/**
 * A stencil — a reusable content block that templates use by reference. A stencil has one
 * editable content; templates that use it render it as it is, so changing it changes them.
 */
data class StencilInfo(
    val id: String,
    val catalogId: String,
    val name: String,
    val description: String?,
    val tags: List<String>,
    /** JSON Schema of the parameters an instance binds; null when it takes none. */
    val parameterSchema: JsonNode?,
    /** Whether its content also sees the using template's data, not only its parameters. */
    val readsTemplateData: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(stencil: Stencil): StencilInfo = StencilInfo(
            id = stencil.id.value,
            catalogId = stencil.catalogKey.value,
            name = stencil.name,
            description = stencil.description,
            tags = stencil.tags,
            parameterSchema = stencil.parameterSchema,
            readsTemplateData = stencil.readsTemplateData,
            createdAt = stencil.createdAt,
            updatedAt = stencil.updatedAt,
        )
    }
}

/** A stencil with its content, for `get_stencil`. */
data class StencilDetailInfo(
    val stencil: StencilInfo,
    val content: TemplateDocument,
) {
    companion object {
        fun from(stencil: Stencil): StencilDetailInfo = StencilDetailInfo(StencilInfo.from(stencil), stencil.content)
    }
}

/** A template variant that uses a stencil, and whether it still fits the stencil as it is. */
data class StencilUsageInfo(
    val catalogId: String,
    val templateId: String,
    val templateName: String,
    val variantId: String,
    val instanceCount: Int,
    val fits: Boolean,
    /** What no longer fits, one sentence each; marking it ready and releasing are refused until fixed. */
    val problems: List<String>,
) {
    companion object {
        fun from(usage: StencilUsageDetail): StencilUsageInfo = StencilUsageInfo(
            catalogId = usage.catalogKey.value,
            templateId = usage.templateId.value,
            templateName = usage.templateName,
            variantId = usage.variantId.value,
            instanceCount = usage.instanceCount,
            fits = usage.fits,
            problems = usage.problems,
        )
    }
}

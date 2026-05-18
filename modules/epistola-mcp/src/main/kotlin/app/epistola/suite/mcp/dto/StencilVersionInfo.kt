package app.epistola.suite.mcp.dto

import app.epistola.suite.stencils.model.StencilVersion
import app.epistola.suite.stencils.model.StencilVersionSummary
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime

/**
 * Summary of a stencil version — metadata + parameter schema.
 *
 * Used by `list_stencil_versions` so the AI can discover which versions
 * declare parameters without fetching the full content.
 */
data class StencilVersionSummaryInfo(
    val version: Int,
    val status: String,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
    /** JSON Schema describing parameters, or null when the version has no parameters. */
    val parameterSchema: JsonNode?,
) {
    companion object {
        fun from(summary: StencilVersionSummary): StencilVersionSummaryInfo = StencilVersionSummaryInfo(
            version = summary.id.value,
            status = summary.status.name.lowercase(),
            createdAt = summary.createdAt,
            publishedAt = summary.publishedAt,
            archivedAt = summary.archivedAt,
            parameterSchema = summary.parameterSchema,
        )
    }
}

/**
 * Full stencil version — metadata + parameter schema + content.
 *
 * Used by `get_stencil_version` so the AI can inspect the version's
 * parameter schema and content (the template document fragment).
 */
data class StencilVersionFullInfo(
    val version: Int,
    val status: String,
    val createdAt: OffsetDateTime,
    val publishedAt: OffsetDateTime?,
    val archivedAt: OffsetDateTime?,
    /** JSON Schema describing parameters, or null when the version has no parameters. */
    val parameterSchema: JsonNode?,
    /** The version's content as a template document fragment. */
    val content: app.epistola.template.model.TemplateDocument?,
) {
    companion object {
        fun from(version: StencilVersion): StencilVersionFullInfo = StencilVersionFullInfo(
            version = version.id.value,
            status = version.status.name.lowercase(),
            createdAt = version.createdAt,
            publishedAt = version.publishedAt,
            archivedAt = version.archivedAt,
            parameterSchema = version.parameterSchema,
            content = version.content,
        )
    }
}

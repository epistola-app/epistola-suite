package app.epistola.suite.mcp.dto

import app.epistola.suite.stencils.Stencil
import java.time.OffsetDateTime

/**
 * A stencil — a reusable content block that templates can embed. Stencils
 * have their own version history (draft + published versions); MCP tools
 * expose stencil metadata so the AI can reference them when designing
 * templates. Authoring stencils is out of scope for the MCP MVP.
 */
data class StencilInfo(
    val id: String,
    val catalogId: String,
    val name: String,
    val description: String?,
    val tags: List<String>,
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
            createdAt = stencil.createdAt,
            updatedAt = stencil.updatedAt,
        )
    }
}

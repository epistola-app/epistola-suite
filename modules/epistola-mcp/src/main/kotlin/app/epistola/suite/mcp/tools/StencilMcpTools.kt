// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mcp.dto.StencilInfo
import app.epistola.suite.mcp.dto.StencilVersionFullInfo
import app.epistola.suite.mcp.dto.StencilVersionSummaryInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.stencils.queries.ListStencils
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class StencilMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_stencils",
        description = "List stencils in the current tenant. A stencil is a reusable content block " +
            "templates can embed. Each stencil has its own version history; this tool exposes metadata " +
            "and tags so the AI can find relevant stencils by name or tag.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listStencils(
        @McpToolParam(
            description = "Catalog key to filter by. Omit to list across all catalogs.",
            required = false,
        )
        catalogId: String?,
        @McpToolParam(
            description = "Substring match against name or description.",
            required = false,
        )
        search: String?,
        @McpToolParam(
            description = "Filter to stencils tagged with the given tag.",
            required = false,
        )
        tag: String?,
    ): List<StencilInfo> = mediator.query(
        ListStencils(
            tenantId = mcpTenantId(),
            catalogKey = catalogId?.let { CatalogKey.of(it) },
            searchTerm = search,
            tag = tag,
        ),
    ).map { StencilInfo.from(it) }

    @McpTool(
        name = "get_stencil",
        description = "Fetch a single stencil's metadata. Use `list_stencils` first to find candidate IDs.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getStencil(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
    ): StencilInfo? {
        val id = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        return mediator.query(GetStencil(id))?.let { StencilInfo.from(it) }
    }

    @McpTool(
        name = "list_stencil_versions",
        description = "List all versions of a stencil, newest first. Each version includes its " +
            "status (draft / published / archived) and its parameter schema if one is declared. " +
            "Use this to discover which versions have parameters before calling `get_stencil_version` " +
            "to fetch the full schema and content.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listStencilVersions(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
    ): List<StencilVersionSummaryInfo> {
        val id = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        return mediator.query(ListStencilVersions(id)).map { StencilVersionSummaryInfo.from(it) }
    }

    @McpTool(
        name = "get_stencil_version",
        description = "Fetch a single stencil version including its full parameter schema and content. " +
            "Use `list_stencil_versions` first to discover available version numbers, then call this " +
            "to inspect the parameter schema (JSON Schema) and content (template document fragment) " +
            "before embedding the stencil in a template.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getStencilVersion(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
        @McpToolParam(description = "Version number (1..N).")
        version: Int,
    ): StencilVersionFullInfo? {
        val tenantId = mcpTenantId()
        val versionId = StencilVersionId(
            VersionKey.of(version),
            StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), tenantId)),
        )
        return mediator.query(GetStencilVersion(versionId))?.let { StencilVersionFullInfo.from(it) }
    }
}

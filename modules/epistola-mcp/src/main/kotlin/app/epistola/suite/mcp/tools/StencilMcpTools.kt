// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.mcp.dto.StencilDetailInfo
import app.epistola.suite.mcp.dto.StencilInfo
import app.epistola.suite.mcp.dto.StencilUsageInfo
import app.epistola.suite.mcp.support.CATALOG_VERSION_DESCRIPTION
import app.epistola.suite.mcp.support.EXPECTED_REVISION_DESCRIPTION
import app.epistola.suite.mcp.support.catalogFilter
import app.epistola.suite.mcp.support.catalogVersionRef
import app.epistola.suite.mcp.support.mcpCatalog
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mcp.support.parseOptionalArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilUsagePage
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.templates.model.TemplateDocument
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Stencils over MCP. A stencil has one editable content that templates use by reference (contract
 * ADR 0006): there are no stencil versions to publish or upgrade to, and changing a stencil changes
 * every template that uses it, which `list_stencil_usages` checks.
 */
@Component
class StencilMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) {

    @McpTool(
        name = "list_stencils",
        description = "List stencils in the current tenant. A stencil is a reusable content block " +
            "templates use by reference; this tool exposes metadata, tags and the parameter schema so the " +
            "AI can find relevant stencils by name or tag.",
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<StencilInfo> = mediator.query(
        ListStencils(
            tenantId = mcpTenantId(),
            catalog = catalogFilter(catalogId),
            searchTerm = search,
            tag = tag,
            catalogVersion = catalogVersionRef(catalogId, catalogVersion),
        ),
    ).map { StencilInfo.from(it) }

    @McpTool(
        name = "get_stencil",
        description = "Fetch a stencil with its content (a template document) and its parameter schema " +
            "(JSON Schema). Use `list_stencils` first to find candidate IDs.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getStencil(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): StencilDetailInfo? = mediator.query(GetStencil(stencilId(catalogId, stencilId), catalogVersion = catalogVersionRef(catalogId, catalogVersion)))?.let { StencilDetailInfo.from(it) }

    @McpTool(
        name = "list_stencil_usages",
        description = "List the template variants that use a stencil, and whether each " +
            "still fits the stencil as it is now: a fill for a placeholder the stencil no longer has, or a " +
            "required parameter left unbound, is listed as a problem. Run it after changing a stencil. " +
            "100 per page.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listStencilUsages(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
        @McpToolParam(description = "1-based page; defaults to 1.", required = false)
        page: Int?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<StencilUsageInfo> {
        val id = stencilId(catalogId, stencilId)
        val version = catalogVersionRef(catalogId, catalogVersion)
        if (GetStencil(id, catalogVersion = version).query() == null) throw notFound("Stencil", catalogId, stencilId)
        return GetStencilUsagePage(id, page = page ?: 1, catalogVersion = version).query().items.map(StencilUsageInfo::from)
    }

    @McpTool(
        name = "create_stencil",
        description = "Create a stencil — a reusable content block, such as a letterhead or a signature — in an " +
            "catalog that can be changed here, with `content` (or an empty document). Templates use it with a `stencil` " +
            "component that names it by `stencilKey`; see the MCP guide for the instance shape. " +
            "Requires the STENCIL_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createStencil(
        @McpToolParam(description = "Catalog key to create the stencil in. Must be a catalog that can be changed here (`editable` in `list_catalogs`).")
        catalogId: String,
        @McpToolParam(description = "Key for the new stencil: lowercase letters, digits and hyphens, e.g. `letterhead`.")
        stencilId: String,
        @McpToolParam(description = "Display name.")
        name: String,
        @McpToolParam(description = "Optional description.", required = false)
        description: String?,
        @McpToolParam(description = "Tags to find the stencil by.", required = false)
        tags: List<String>?,
        @McpToolParam(
            description = "Content: a template document as a JSON object string, same shape as " +
                "`update_variant_content` takes. It may use other stencils. Omit for an empty document.",
            required = false,
        )
        content: String?,
        @McpToolParam(
            description = "JSON Schema object string declaring the stencil's parameters. Omit for none.",
            required = false,
        )
        parameterSchema: String?,
        @McpToolParam(
            description = "Whether the content also reads the using template's data, not only the stencil's " +
                "parameters. Defaults to false.",
            required = false,
        )
        readsTemplateData: Boolean?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): StencilInfo = StencilInfo.from(
        CreateStencil(
            id = stencilId(catalogId, stencilId),
            name = name,
            description = description?.takeIf { it.isNotBlank() },
            tags = tags.orEmpty(),
            content = objectMapper.parseOptionalArgument("content", content, TemplateDocument::class.java),
            parameterSchema = objectMapper.parseOptionalArgument("parameterSchema", parameterSchema, JsonNode::class.java),
            readsTemplateData = readsTemplateData ?: false,
            catalogVersion = catalogVersionRef(catalogId, catalogVersion),
        ).execute(),
    )

    @McpTool(
        name = "update_stencil",
        description = "Update a stencil. Omitted arguments are left unchanged; given `tags` replace all tags. A new " +
            "`content` or `parameterSchema` takes effect in every template that uses the stencil: check " +
            "`list_stencil_usages` afterwards. Requires the STENCIL_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateStencil(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
        @McpToolParam(description = "New display name.", required = false)
        name: String?,
        @McpToolParam(description = "New description.", required = false)
        description: String?,
        @McpToolParam(description = "Tags; replaces all current tags.", required = false)
        tags: List<String>?,
        @McpToolParam(description = "The complete new content: a template document as a JSON object string.", required = false)
        content: String?,
        @McpToolParam(description = "JSON Schema object string replacing the parameter schema. Left out keeps it; `{}` removes it.", required = false)
        parameterSchema: String?,
        @McpToolParam(description = "True to remove the parameter schema, the same as `parameterSchema` `{}`; ignored when `parameterSchema` is given.", required = false)
        clearParameterSchema: Boolean?,
        @McpToolParam(description = "Whether the content also reads the using template's data.", required = false)
        readsTemplateData: Boolean?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
        @McpToolParam(description = EXPECTED_REVISION_DESCRIPTION, required = false)
        expectedRevision: Long? = null,
    ): StencilInfo = UpdateStencil(
        id = stencilId(catalogId, stencilId),
        name = name?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        tags = tags,
        content = objectMapper.parseOptionalArgument("content", content, TemplateDocument::class.java),
        parameterSchema = objectMapper.parseOptionalArgument("parameterSchema", parameterSchema, JsonNode::class.java),
        clearParameterSchema = parameterSchema == null && clearParameterSchema == true,
        readsTemplateData = readsTemplateData,
        catalogVersion = catalogVersionRef(catalogId, catalogVersion),
        expectedRevision = expectedRevision,
    ).execute()?.let { StencilInfo.from(it) } ?: throw notFound("Stencil", catalogId, stencilId)

    private fun stencilId(catalogId: String, stencilId: String) = StencilId(StencilKey.of(stencilId), mcpCatalog(catalogId))
}

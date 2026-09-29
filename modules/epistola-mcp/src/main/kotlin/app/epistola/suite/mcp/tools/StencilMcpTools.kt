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
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mcp.support.parseArgument
import app.epistola.suite.mcp.support.parseOptionalArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.CreateStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.stencils.commands.UpdateStencilDraft
import app.epistola.suite.stencils.model.StencilVersionStatus
import app.epistola.suite.stencils.queries.GetStencil
import app.epistola.suite.stencils.queries.GetStencilVersion
import app.epistola.suite.stencils.queries.ListStencilVersions
import app.epistola.suite.stencils.queries.ListStencils
import app.epistola.suite.templates.model.TemplateDocument
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@Component
class StencilMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
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

    @McpTool(
        name = "create_stencil",
        description = "Create a stencil — a reusable content block, such as a letterhead or a signature — in an " +
            "AUTHORED catalog. It starts with a draft version holding `content` (or an empty document). " +
            "Templates embed a stencil with a `stencil` component; a draft template may reference the draft, " +
            "publishing the template needs a published stencil version: see `publish_stencil_version` and " +
            "`upgrade_stencil_in_template`. " +
            "Requires the STENCIL_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createStencil(
        @McpToolParam(description = "Catalog key to create the stencil in. Must be an AUTHORED catalog.")
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
            description = "Initial draft content: a template document as a JSON object string, same shape as " +
                "`update_template_draft` takes. Stencils cannot contain other stencils. Omit for an empty draft.",
            required = false,
        )
        content: String?,
        @McpToolParam(
            description = "JSON Schema object string declaring the stencil's parameters. Omit for none.",
            required = false,
        )
        parameterSchema: String?,
    ): StencilInfo = StencilInfo.from(
        CreateStencil(
            id = stencilId(catalogId, stencilId),
            name = name,
            description = description?.takeIf { it.isNotBlank() },
            tags = tags.orEmpty(),
            content = objectMapper.parseOptionalArgument("content", content, TemplateDocument::class.java),
            parameterSchema = objectMapper.parseOptionalArgument("parameterSchema", parameterSchema, JsonNode::class.java),
        ).execute(),
    )

    @McpTool(
        name = "update_stencil",
        description = "Update a stencil's metadata: name, description, tags. Omitted arguments are left " +
            "unchanged; given `tags` replace all tags. Change content with `update_stencil_draft`. " +
            "Requires the STENCIL_EDIT permission.",
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
    ): StencilInfo = UpdateStencil(
        id = stencilId(catalogId, stencilId),
        name = name?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        tags = tags,
    ).execute()?.let { StencilInfo.from(it) } ?: throw notFound("Stencil", catalogId, stencilId)

    @McpTool(
        name = "update_stencil_draft",
        description = "Replace the content of a stencil's draft version, creating a new draft from the latest " +
            "version when the stencil has none. Published versions are never changed; publish the draft with " +
            "`publish_stencil_version`. Returns the draft's " +
            "version number and parameter schema. Requires the STENCIL_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateStencilDraft(
        @McpToolParam(description = "Catalog key the stencil belongs to.")
        catalogId: String,
        @McpToolParam(description = "Stencil key.")
        stencilId: String,
        @McpToolParam(description = "The complete stencil content: a template document as a JSON object string.")
        content: String,
        @McpToolParam(
            description = "JSON Schema object string declaring the stencil's parameters. Omit to keep the " +
                "current draft's (or, for a new draft, the latest version's) schema.",
            required = false,
        )
        parameterSchema: String?,
    ): StencilVersionSummaryInfo {
        val id = stencilId(catalogId, stencilId)
        val document = objectMapper.parseArgument("content", content, TemplateDocument::class.java)
        val schema = objectMapper.parseOptionalArgument("parameterSchema", parameterSchema, JsonNode::class.java)
        if (GetStencil(id).query() == null) throw notFound("Stencil", catalogId, stencilId)
        val draft = ListStencilVersions(id).query().firstOrNull { it.status == StencilVersionStatus.DRAFT }
        val version = if (draft == null) {
            CreateStencilVersion(
                stencilId = id,
                content = document,
                parameterSchema = schema,
                inheritParameterSchemaFromSource = schema == null,
            ).execute() ?: throw notFound("Stencil", catalogId, stencilId)
        } else {
            // UpdateStencilDraft stores the schema it is given, so an omitted one is carried over.
            UpdateStencilDraft(
                versionId = StencilVersionId(draft.id, id),
                content = document,
                parameterSchema = schema ?: draft.parameterSchema,
            ).execute()
        }
        return StencilVersionSummaryInfo.from(version)
    }

    private fun stencilId(catalogId: String, stencilId: String) = StencilId(StencilKey.of(stencilId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
}

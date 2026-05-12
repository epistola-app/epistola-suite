package app.epistola.suite.mcp.tools

import app.epistola.suite.attributes.codelists.queries.GetCodeList
import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.codelists.queries.ListCodeLists
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mcp.dto.CodeListEntryInfo
import app.epistola.suite.mcp.dto.CodeListInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

/**
 * Read-only discovery tools for code lists. AI clients use these to learn
 * which canonical value sets a variant attribute can accept — e.g. the
 * bundled `system/bcp-47` locale tags that `system.locale` binds to.
 *
 * Writes are intentionally out of scope (the MCP MVP is read-only); use the
 * REST API for create/update/delete/refresh.
 */
@Component
class CodeListMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_code_lists",
        description = "List code lists available in the current tenant. " +
            "Optionally narrow by catalog (e.g. `system` for the bundled " +
            "canonical lists: bcp-47, iso-639-1, iso-3166-1-alpha2).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCodeLists(
        @McpToolParam(description = "Catalog slug to filter by. Omit to list all.", required = false)
        catalogId: String?,
    ): List<CodeListInfo> {
        val tenantId = TenantId(mcpTenantKey())
        val filter = catalogId?.takeIf { it.isNotBlank() }?.let { CatalogKey.of(it) }
        return mediator.query(ListCodeLists(tenantId = tenantId, catalogKey = filter))
            .map { CodeListInfo.from(it) }
    }

    @McpTool(
        name = "get_code_list",
        description = "Fetch one code list by `catalogId` + `codeListSlug`. " +
            "Includes source metadata (INLINE/URL), readOnly flag, and the " +
            "last-refresh status for URL-sourced lists.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getCodeList(
        @McpToolParam(description = "Catalog the code list lives in (e.g. `system`).")
        catalogId: String,
        @McpToolParam(description = "Slug of the code list (e.g. `iso-639-1`).")
        codeListSlug: String,
    ): CodeListInfo? {
        val id = buildId(catalogId, codeListSlug)
        return mediator.query(GetCodeList(id = id))?.let { CodeListInfo.from(it) }
    }

    @McpTool(
        name = "list_code_list_entries",
        description = "List the `{code, label}` entries of a code list. " +
            "Hidden entries are filtered out unless `includeHidden=true`.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCodeListEntries(
        @McpToolParam(description = "Catalog the code list lives in.")
        catalogId: String,
        @McpToolParam(description = "Slug of the code list.")
        codeListSlug: String,
        @McpToolParam(description = "Include hidden entries (default false).", required = false)
        includeHidden: Boolean?,
    ): List<CodeListEntryInfo> {
        val id = buildId(catalogId, codeListSlug)
        return mediator.query(ListCodeListEntries(codeListId = id, includeHidden = includeHidden ?: false))
            .map { CodeListEntryInfo.from(it) }
    }

    private fun buildId(catalogId: String, codeListSlug: String) = CodeListId(
        key = CodeListKey.of(codeListSlug),
        catalogId = CatalogId(CatalogKey.of(catalogId), TenantId(mcpTenantKey())),
    )
}

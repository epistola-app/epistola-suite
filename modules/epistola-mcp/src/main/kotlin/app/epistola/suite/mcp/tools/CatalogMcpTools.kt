package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.mcp.dto.CatalogInfo
import app.epistola.suite.mcp.dto.CatalogUpgradeDiffInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class CatalogMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_catalogs",
        description = "List catalogs available in the current tenant. " +
            "Catalogs hold templates, themes, and stencils; most other tools " +
            "require a `catalogId` argument that comes from this list. " +
            "AUTHORED catalogs are editable; SUBSCRIBED catalogs are read-only mirrors of remote sources.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listCatalogs(): List<CatalogInfo> = mediator.query(ListCatalogs(mcpTenantKey()))
        .map { CatalogInfo.from(it) }

    @McpTool(
        name = "preview_catalog_upgrade",
        description = "Preview what upgrading a SUBSCRIBED catalog to its source's latest release " +
            "would do, WITHOUT applying it. Re-fetches the source manifest and diffs it against the " +
            "installed release: added / removed / changed / unchanged resources (each `type/slug`), " +
            "plus any cross-catalog conflicts that would block removals. Read-only — the upgrade " +
            "action itself is not available over MCP. `catalogId` comes from `list_catalogs` " +
            "(SUBSCRIBED catalogs only).",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun previewCatalogUpgrade(
        @McpToolParam(description = "Catalog key of a SUBSCRIBED catalog. From `list_catalogs`.")
        catalogId: String,
    ): CatalogUpgradeDiffInfo = CatalogUpgradeDiffInfo.from(
        mediator.query(PreviewCatalogUpgrade(mcpTenantKey(), CatalogKey.of(catalogId))),
    )
}

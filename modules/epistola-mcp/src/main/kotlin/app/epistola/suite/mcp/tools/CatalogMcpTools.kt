package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.mcp.dto.CatalogInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import org.springframework.ai.mcp.annotation.McpTool
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
}

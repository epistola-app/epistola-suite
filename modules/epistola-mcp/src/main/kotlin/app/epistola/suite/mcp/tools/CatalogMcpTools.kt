// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.queries.PreviewCatalogUpgrade
import app.epistola.suite.mcp.dto.CatalogInfo
import app.epistola.suite.mcp.dto.CatalogUpgradeDiffInfo
import app.epistola.suite.mcp.support.mcpTenantKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
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
        name = "create_catalog",
        description = "Create an empty AUTHORED catalog in the current tenant, to hold the templates, themes, " +
            "stencils and images you then create in it (pass its key as `catalogId`). Fails when a catalog " +
            "with that key exists. Requires the CATALOG_MANAGE permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createCatalog(
        @McpToolParam(
            description = "Key for the new catalog: 3-50 lowercase letters, digits and single hyphens, " +
                "starting with a letter, e.g. `sociaal-domein`.",
        )
        catalogId: String,
        @McpToolParam(description = "Display name.")
        name: String,
        @McpToolParam(description = "Optional description.", required = false)
        description: String?,
    ): CatalogInfo = CatalogInfo.from(
        CreateCatalog(
            tenantKey = mcpTenantKey(),
            id = CatalogKey.of(catalogId),
            name = name,
            description = description?.takeIf { it.isNotBlank() },
        ).execute(),
    )

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

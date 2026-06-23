package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.mcp.dto.ThemeInfo
import app.epistola.suite.mcp.dto.ThemeSummaryInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class ThemeMcpTools(
    private val mediator: Mediator,
) {

    @McpTool(
        name = "list_themes",
        description = "List themes in the current tenant. Themes carry document styles, page settings, " +
            "and named block style presets. Templates reference a theme by id; use this to discover " +
            "what styling a template can pick from.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun listThemes(
        @McpToolParam(
            description = "Catalog key to filter by. Omit to list across all catalogs.",
            required = false,
        )
        catalogId: String?,
        @McpToolParam(
            description = "Case-insensitive substring match against the theme name. Omit for no filter.",
            required = false,
        )
        search: String?,
    ): List<ThemeSummaryInfo> = mediator.query(
        ListThemes(
            tenantId = mcpTenantId(),
            catalogKey = catalogId?.let { CatalogKey.of(it) },
            searchTerm = search,
            page = PageRequest.ALL,
        ),
    ).items.map { ThemeSummaryInfo.from(it) }

    @McpTool(
        name = "get_theme",
        description = "Fetch full theme details: document styles, page settings, block style presets. " +
            "Returns the structured style definitions the renderer applies.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getTheme(
        @McpToolParam(description = "Catalog key the theme belongs to.")
        catalogId: String,
        @McpToolParam(description = "Theme key.")
        themeId: String,
    ): ThemeInfo? {
        val id = ThemeId(ThemeKey.of(themeId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        return mediator.query(GetTheme(id))?.let { ThemeInfo.from(it) }
    }
}

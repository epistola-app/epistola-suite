// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mcp.dto.ThemeInfo
import app.epistola.suite.mcp.dto.ThemeSummaryInfo
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mcp.support.parseOptionalArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.themes.BlockStylePresets
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.ListThemes
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ThemeMcpTools(
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
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
        ),
    ).map { ThemeSummaryInfo.from(it) }

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

    @McpTool(
        name = "create_theme",
        description = "Create a theme in an AUTHORED catalog: document-level styles, page settings and named " +
            "block style presets that templates share. Each styling argument is a JSON string in the shape " +
            "`get_theme` returns for the same field. Apply it to a template with `update_template`. " +
            "Requires the THEME_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createTheme(
        @McpToolParam(description = "Catalog key to create the theme in. Must be an AUTHORED catalog.")
        catalogId: String,
        @McpToolParam(description = "Key for the new theme: lowercase letters, digits and hyphens, e.g. `corporate`.")
        themeId: String,
        @McpToolParam(description = "Display name.")
        name: String,
        @McpToolParam(description = "Optional description.", required = false)
        description: String?,
        @McpToolParam(description = DOCUMENT_STYLES, required = false)
        documentStyles: String?,
        @McpToolParam(description = PAGE_SETTINGS, required = false)
        pageSettings: String?,
        @McpToolParam(description = BLOCK_STYLE_PRESETS, required = false)
        blockStylePresets: String?,
        @McpToolParam(description = SPACING_UNIT, required = false)
        spacingUnit: Float?,
    ): ThemeInfo = ThemeInfo.from(
        CreateTheme(
            id = themeId(catalogId, themeId),
            name = name,
            description = description?.takeIf { it.isNotBlank() },
            documentStyles = parseDocumentStyles(documentStyles) ?: emptyMap(),
            pageSettings = objectMapper.parseOptionalArgument("pageSettings", pageSettings, PageSettings::class.java),
            blockStylePresets = objectMapper.parseOptionalArgument(
                "blockStylePresets",
                blockStylePresets,
                BlockStylePresets::class.java,
            ),
            spacingUnit = spacingUnit,
        ).execute(),
    )

    @McpTool(
        name = "update_theme",
        description = "Update a theme. Omitted arguments are left unchanged; a given styling argument replaces " +
            "that whole field, so read the theme with `get_theme` first and send back the merged value. " +
            "Templates using the theme pick up the change in drafts and in versions published afterwards. " +
            "Requires the THEME_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun updateTheme(
        @McpToolParam(description = "Catalog key the theme belongs to.")
        catalogId: String,
        @McpToolParam(description = "Theme key.")
        themeId: String,
        @McpToolParam(description = "New display name.", required = false)
        name: String?,
        @McpToolParam(description = "New description.", required = false)
        description: String?,
        @McpToolParam(description = DOCUMENT_STYLES, required = false)
        documentStyles: String?,
        @McpToolParam(description = PAGE_SETTINGS, required = false)
        pageSettings: String?,
        @McpToolParam(description = BLOCK_STYLE_PRESETS, required = false)
        blockStylePresets: String?,
        @McpToolParam(description = SPACING_UNIT, required = false)
        spacingUnit: Float?,
    ): ThemeInfo = UpdateTheme(
        id = themeId(catalogId, themeId),
        name = name?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        documentStyles = parseDocumentStyles(documentStyles),
        pageSettings = objectMapper.parseOptionalArgument("pageSettings", pageSettings, PageSettings::class.java),
        blockStylePresets = objectMapper.parseOptionalArgument(
            "blockStylePresets",
            blockStylePresets,
            BlockStylePresets::class.java,
        ),
        spacingUnit = spacingUnit,
    ).execute()?.let { ThemeInfo.from(it) } ?: throw notFound("Theme", catalogId, themeId)

    @Suppress("UNCHECKED_CAST")
    private fun parseDocumentStyles(json: String?): DocumentStyles? = objectMapper.parseOptionalArgument("documentStyles", json, Map::class.java) as DocumentStyles?

    private fun themeId(catalogId: String, themeId: String) = ThemeId(ThemeKey.of(themeId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))

    private companion object {
        const val DOCUMENT_STYLES = "JSON object string of document-level style defaults, e.g. " +
            "`{\"fontSize\": \"10pt\", \"color\": \"#222222\"}`. `get_authoring_schemas` lists the style keys and units."
        const val PAGE_SETTINGS = "JSON object string of page settings, e.g. " +
            "`{\"format\": \"A4\", \"orientation\": \"portrait\", \"margins\": {\"top\": 20, \"right\": 20, \"bottom\": 20, \"left\": 20}}` " +
            "(margins in mm)."
        const val BLOCK_STYLE_PRESETS = "JSON object string of named block style presets, same shape as " +
            "`get_theme` returns."
        const val SPACING_UNIT = "Spacing base unit in points (default 4)."
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.tools

import app.epistola.suite.catalog.releases.ApplyHouseTheme
import app.epistola.suite.catalog.releases.ApplyHouseThemeResult
import app.epistola.suite.catalog.releases.AvailablePreset
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.CatalogPreset
import app.epistola.suite.catalog.releases.GetAvailablePresets
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.catalog.releases.SetCatalogPresets
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mcp.dto.ThemeInfo
import app.epistola.suite.mcp.dto.ThemeSummaryInfo
import app.epistola.suite.mcp.support.CATALOG_VERSION_DESCRIPTION
import app.epistola.suite.mcp.support.EXPECTED_REVISION_DESCRIPTION
import app.epistola.suite.mcp.support.catalogFilter
import app.epistola.suite.mcp.support.catalogVersionRef
import app.epistola.suite.mcp.support.mcpTenantId
import app.epistola.suite.mcp.support.notFound
import app.epistola.suite.mcp.support.parseOptionalArgument
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): List<ThemeSummaryInfo> = mediator.query(
        ListThemes(
            tenantId = mcpTenantId(),
            catalogKey = catalogFilter(catalogId),
            searchTerm = search,
            catalogVersion = catalogVersionRef(catalogId, catalogVersion),
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): ThemeInfo? {
        val id = ThemeId(ThemeKey.of(themeId), CatalogId(CatalogKey.of(catalogId), mcpTenantId()))
        return mediator.query(GetTheme(id, catalogVersion = catalogVersionRef(catalogId, catalogVersion)))?.let { ThemeInfo.from(it) }
    }

    @McpTool(
        name = "create_theme",
        description = "Create a theme in a catalog that can be changed here: document-level styles, page settings and named " +
            "block style presets that templates share. Each styling argument is a JSON string in the shape " +
            "`get_theme` returns for the same field. Apply it to a template with `update_template`. " +
            "Requires the THEME_EDIT permission.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun createTheme(
        @McpToolParam(description = "Catalog key to create the theme in. Must be a catalog that can be changed here (`editable` in `list_catalogs`).")
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
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
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
            catalogVersion = catalogVersionRef(catalogId, catalogVersion),
        ).execute(),
    )

    @McpTool(
        name = "update_theme",
        description = "Update a theme. Omitted arguments are left unchanged; a given styling argument replaces " +
            "that whole field, so read the theme with `get_theme` first and send back the merged value. " +
            "To add, change or drop single presets without resending the others, use `mergeBlockStylePresets` " +
            "and `removeBlockStylePresets` instead of `blockStylePresets`. " +
            "Templates using the theme pick up the change at once; releases keep the theme they were cut with. " +
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
        @McpToolParam(
            description = "JSON object string of presets to add or replace by name, same shape as `blockStylePresets`. " +
                "Presets not named are kept. Cannot be combined with `blockStylePresets`.",
            required = false,
        )
        mergeBlockStylePresets: String?,
        @McpToolParam(
            description = "JSON array string of preset names to remove, e.g. `[\"oud-kader\"]`. Names that do not exist " +
                "are ignored. Cannot be combined with `blockStylePresets`.",
            required = false,
        )
        removeBlockStylePresets: String?,
        @McpToolParam(description = SPACING_UNIT, required = false)
        spacingUnit: Float?,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
        @McpToolParam(description = EXPECTED_REVISION_DESCRIPTION, required = false)
        expectedRevision: Long? = null,
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
        mergeBlockStylePresets = objectMapper.parseOptionalArgument(
            "mergeBlockStylePresets",
            mergeBlockStylePresets,
            BlockStylePresets::class.java,
        ),
        removeBlockStylePresets = objectMapper.parseOptionalArgument(
            "removeBlockStylePresets",
            removeBlockStylePresets,
            Array<String>::class.java,
        )?.toSet().orEmpty(),
        spacingUnit = spacingUnit,
        catalogVersion = catalogVersionRef(catalogId, catalogVersion),
        expectedRevision = expectedRevision,
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

    /** A catalog version's theme settings (contract ADR 0007), as MCP returns them. */
    data class CatalogThemeSettingsInfo(
        /** The theme its templates that name none render with: `catalogKey/themeKey`; null for none. */
        val defaultTheme: String?,
        /** The presets the version declares. */
        val presets: List<CatalogPreset>,
        /** The presets its content may use: its own, then its dependencies', each key once. */
        val availablePresets: List<AvailablePreset>,
    )

    @McpTool(
        name = "get_catalog_theme_settings",
        description = "The theme settings of a catalog's draft or release: its default theme (the theme its templates that name " +
            "none render with) and the block style presets it declares, plus the presets its content may use " +
            "(its own and its dependencies'). A block's stylePreset must be one of those once any are declared.",
        annotations = McpTool.McpAnnotations(readOnlyHint = true, idempotentHint = true),
    )
    fun getCatalogThemeSettings(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): CatalogThemeSettingsInfo = settingsInfo(catalogId, catalogVersion)

    @McpTool(
        name = "set_catalog_theme_settings",
        description = "Set a catalog draft's default theme and/or the block style presets it declares. The default " +
            "theme may be in the catalog itself or in a catalog it depends on (declared as a use of it). " +
            "Omitted arguments are kept. `presets` replaces the declared presets: a JSON array of " +
            "{\"key\", \"name\", \"description\"?}; keys are lowercase with dashes, each once.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = true),
    )
    fun setCatalogThemeSettings(
        @McpToolParam(description = "Catalog key.")
        catalogId: String,
        @McpToolParam(description = "Theme key of the new default theme.", required = false)
        defaultThemeKey: String? = null,
        @McpToolParam(description = "Catalog of the default theme; omit for this catalog.", required = false)
        defaultThemeCatalogId: String? = null,
        @McpToolParam(description = "True to clear the default theme; ignored when defaultThemeKey is given.", required = false)
        clearDefaultTheme: Boolean? = null,
        @McpToolParam(description = "JSON array of presets replacing the declared ones.", required = false)
        presets: String? = null,
        @McpToolParam(description = CATALOG_VERSION_DESCRIPTION, required = false)
        catalogVersion: String? = null,
    ): CatalogThemeSettingsInfo {
        val tenantKey = mcpTenantId().key
        val catalogKey = CatalogKey.of(catalogId)
        val version = catalogVersionRef(catalogId, catalogVersion)
        if (defaultThemeKey != null || clearDefaultTheme == true) {
            val theme = defaultThemeKey?.let { CatalogDefaultTheme(ThemeKey.of(it), defaultThemeCatalogId?.let(CatalogKey::of) ?: catalogKey) }
            SetCatalogDefaultTheme(tenantKey, catalogKey, theme, catalogVersion = version).execute()
        }
        if (presets != null) {
            val declared = objectMapper.readTree(presets).iterator().asSequence().map { node ->
                CatalogPreset(
                    key = node.get("key").asString(),
                    name = node.get("name")?.asString() ?: node.get("key").asString(),
                    description = node.get("description")?.takeUnless { it.isNull }?.asString(),
                )
            }.toList()
            SetCatalogPresets(tenantKey, catalogKey, declared, catalogVersion = version).execute()
        }
        return settingsInfo(catalogId, catalogVersion)
    }

    @McpTool(
        name = "apply_house_theme",
        description = "Make the tenant's house theme (its default theme, never used while rendering) the default theme " +
            "of every catalog the tenant authors, in each one's next release. Each takes it on its next publish. " +
            "Returns which catalogs took it and which had no next release.",
        annotations = McpTool.McpAnnotations(readOnlyHint = false, idempotentHint = true),
    )
    fun applyHouseTheme(): ApplyHouseThemeResult = ApplyHouseTheme(mcpTenantId().key).execute()

    private fun settingsInfo(catalogId: String, catalogVersion: String?): CatalogThemeSettingsInfo {
        val tenantKey = mcpTenantId().key
        val catalogKey = CatalogKey.of(catalogId)
        val version = catalogVersionRef(catalogId, catalogVersion)
        val settings = GetCatalogThemeSettings(tenantKey, catalogKey, version).query()
        return CatalogThemeSettingsInfo(
            defaultTheme = settings.defaultTheme?.let { "${it.catalogKey.value}/${it.themeKey.value}" },
            presets = settings.presets,
            availablePresets = GetAvailablePresets(tenantKey, catalogKey, version).query(),
        )
    }
}

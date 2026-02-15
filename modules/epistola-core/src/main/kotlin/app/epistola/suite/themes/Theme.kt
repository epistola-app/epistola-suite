package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * A named block style preset (like a CSS class for blocks).
 *
 * @param label Human-readable label for the preset
 * @param styles CSS-like style properties for this preset
 * @param applicableTo Node types this preset can be applied to (empty/null means all types)
 */
data class BlockStylePreset(
    val label: String,
    val styles: Map<String, Any>,
    val applicableTo: List<String>? = null,
)

/**
 * A theme defines reusable styling that can be applied across multiple templates.
 *
 * Themes provide:
 * - Document-level styles (font, color, alignment defaults)
 * - Optional page settings (format, orientation, margins)
 * - Named block style presets (like CSS classes for blocks)
 *
 * Templates reference a theme via themeRef in TemplateDocument. Style cascade order:
 * 1. Theme document styles (lowest priority)
 * 2. Template document styles (override theme)
 * 3. Theme block preset (when block has stylePreset)
 * 4. Block inline styles (highest priority)
 */
data class Theme(
    val id: ThemeId,
    val tenantId: TenantId,
    val name: String,
    val description: String?,
    @Json val documentStyles: DocumentStyles,
    @Json val pageSettings: PageSettings?,
    @Json val blockStylePresets: Map<String, BlockStylePreset>?,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

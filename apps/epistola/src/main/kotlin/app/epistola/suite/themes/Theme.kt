package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

/**
 * A theme defines reusable styling that can be applied across multiple templates.
 *
 * Themes provide:
 * - Document-level styles (font, color, alignment defaults)
 * - Optional page settings (format, orientation, margins)
 * - Named block style presets (like CSS classes for blocks)
 *
 * Templates reference a theme via themeId in TemplateModel. Style cascade order:
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
    @Json val blockStylePresets: Map<String, Map<String, Any>>?,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)

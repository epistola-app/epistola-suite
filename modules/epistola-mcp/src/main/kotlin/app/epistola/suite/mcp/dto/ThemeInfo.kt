// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.themes.Theme
import java.time.OffsetDateTime

/**
 * Lightweight theme record for list views.
 */
data class ThemeSummaryInfo(
    val id: String,
    val catalogId: String,
    val name: String,
    val description: String?,
) {
    companion object {
        fun from(theme: Theme): ThemeSummaryInfo = ThemeSummaryInfo(
            id = theme.id.value,
            catalogId = theme.catalogKey.value,
            name = theme.name,
            description = theme.description,
        )
    }
}

/**
 * Full theme record — styling rules a template can apply.
 *
 * `documentStyles` carries document-level defaults (font, color, alignment),
 * `pageSettings` describes page format/margins, and `blockStylePresets` is a
 * map of named presets that can be applied to individual blocks.
 */
data class ThemeInfo(
    val id: String,
    val catalogId: String,
    val name: String,
    val description: String?,
    val documentStyles: Any,
    val pageSettings: Any?,
    val blockStylePresets: Any?,
    /** Spacing base unit in points (default 4pt when null). */
    val spacingUnit: Float?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(theme: Theme): ThemeInfo = ThemeInfo(
            id = theme.id.value,
            catalogId = theme.catalogKey.value,
            name = theme.name,
            description = theme.description,
            documentStyles = theme.documentStyles,
            pageSettings = theme.pageSettings,
            blockStylePresets = theme.blockStylePresets,
            spacingUnit = theme.spacingUnit,
            createdAt = theme.createdAt,
            updatedAt = theme.updatedAt,
        )
    }
}

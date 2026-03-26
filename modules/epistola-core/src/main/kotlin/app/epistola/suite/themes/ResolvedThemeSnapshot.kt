package app.epistola.suite.themes

import app.epistola.generation.pdf.SpacingScale
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings

/**
 * An immutable snapshot of the resolved theme captured at publish time.
 *
 * When a template version is published, the live theme cascade is resolved once and stored
 * alongside the version. All subsequent generations of that published version use this snapshot
 * instead of re-resolving the theme from the database, guaranteeing deterministic output.
 *
 * For draft previews, the live theme cascade is still used so editors see changes immediately.
 *
 * @param themeKey The resolved theme key at publish time (null if no theme was active)
 * @param documentStyles The merged document styles (theme + template overrides)
 * @param pageSettings The theme's page settings fallback (null if no theme or no page settings)
 * @param blockStylePresets The theme's block style presets as a plain map (preset name -> styles map)
 * @param spacingUnit The theme's spacing base unit in points (see [SpacingScale])
 */
data class ResolvedThemeSnapshot(
    val themeKey: ThemeKey?,
    val documentStyles: DocumentStyles,
    val pageSettings: PageSettings?,
    val blockStylePresets: Map<String, Map<String, Any>>,
    val spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
) {
    companion object {
        /**
         * Creates a snapshot from a [ResolvedStyles] result.
         */
        fun from(resolvedStyles: ResolvedStyles, themeKey: ThemeKey?): ResolvedThemeSnapshot = ResolvedThemeSnapshot(
            themeKey = themeKey,
            documentStyles = resolvedStyles.documentStyles,
            pageSettings = resolvedStyles.pageSettings,
            blockStylePresets = resolvedStyles.blockStylePresets.mapValues { (_, preset) -> preset.styles },
            spacingUnit = resolvedStyles.spacingUnit,
        )
    }
}

package app.epistola.generation.pdf

import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.PageSettings

/**
 * Bundles the theme-derived inputs that the PDF renderer needs.
 *
 * All fields originate from the same `Theme` (via the suite's
 * `ThemeStyleResolver` for live previews, or a `ResolvedThemeSnapshot`
 * for published versions). Grouping them keeps the renderer's `render()`
 * signature short and makes the cascade explicit:
 *
 *   template-level overrides → this resolved theme → engine defaults
 *
 * Each field falls back to the engine defaults when null/empty.
 */
data class ResolvedTheme(
    /** Inheritable typography (fontFamily, fontSize, color, …) merged from theme + template. */
    val documentStyles: DocumentStyles? = null,
    /** Page format / orientation / margins. Theme-level fallback for `document.pageSettingsOverride`. */
    val pageSettings: PageSettings? = null,
    /** Named block style presets (CSS-class-like) referenced by node `stylePreset`. */
    val blockStylePresets: Map<String, Map<String, Any>> = emptyMap(),
    /** Spacing scale base unit in points (controls `Nsp` resolution). */
    val spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
)

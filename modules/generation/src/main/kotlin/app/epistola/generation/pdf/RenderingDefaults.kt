package app.epistola.generation.pdf

import app.epistola.template.model.Margins
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageSettings

/**
 * Versioned set of rendering constants used during PDF generation.
 *
 * Every hardcoded rendering default (font sizes, margins, spacing, borders) is captured here
 * so that published template versions can be re-rendered identically even after Epistola upgrades.
 *
 * When a template version is published, the current [version] number is stored alongside it.
 * At generation time, the matching [RenderingDefaults] is loaded via [forVersion], guaranteeing
 * that the same constants are used regardless of what [CURRENT] is at that point.
 *
 * **Rules for evolving defaults:**
 * 1. Never modify an existing version — create a new version instead.
 * 2. Bump [CURRENT] to the new version so new publishes pick it up.
 * 3. Old published versions keep rendering with their original defaults.
 */
data class RenderingDefaults(
    val version: Int,

    // -- Page settings (fallback when template has no pageSettingsOverride) --
    val defaultPageSettings: PageSettings,

    // -- Component spacing (baseline marginBottom per component type) --
    val componentSpacing: Map<String, Map<String, Any>>,

    // -- Typography: heading sizes (level -> pt) --
    val headingSizes: Map<Int, Float>,

    // -- Typography: heading vertical margins (level -> pt) --
    val headingMargins: Map<Int, Float>,

    // -- Typography: paragraph --
    val paragraphMarginBottom: Float,

    // -- Typography: lists --
    val listMarginBottom: Float,
    val listMarginLeft: Float,
    val listItemMarginBottom: Float,

    // -- Tables --
    val tableBorderWidth: Float,
    val tableBorderColorHex: String,
    val tableCellPadding: Float,
    val datatableDefaultColumnWidthPercent: Float,

    // -- Columns --
    val columnGap: Float,

    // -- Unit conversion --
    val baseFontSizePt: Float,
) {
    companion object {
        /**
         * V1: Initial rendering defaults capturing all hardcoded values as of the first release.
         * These values must NEVER be changed — create V2 instead.
         */
        val V1 = RenderingDefaults(
            version = 1,
            defaultPageSettings = PageSettings(
                format = PageFormat.A4,
                orientation = Orientation.portrait,
                margins = Margins(top = 20, right = 20, bottom = 20, left = 20),
            ),
            componentSpacing = mapOf(
                "text" to mapOf("marginBottom" to "0.5em"),
                "container" to mapOf("marginBottom" to "0.5em"),
                "columns" to mapOf("marginBottom" to "0.5em"),
                "table" to mapOf("marginBottom" to "0.5em"),
                "datatable" to mapOf("marginBottom" to "0.5em"),
                "image" to mapOf("marginBottom" to "0.5em"),
            ),
            headingSizes = mapOf(
                1 to 24f, // 2em
                2 to 18f, // 1.5em
                3 to 14f, // 1.17em
            ),
            headingMargins = mapOf(
                1 to 9.6f, // 0.4em × 24pt
                2 to 5.4f, // 0.3em × 18pt
                3 to 2.8f, // 0.2em × 14pt
            ),
            paragraphMarginBottom = 6f, // 0.5em × 12pt
            listMarginBottom = 3.6f, // 0.3em × 12pt
            listMarginLeft = 18f, // 1.5em × 12pt
            listItemMarginBottom = 1.8f, // 0.15em × 12pt
            tableBorderWidth = 0.5f,
            tableBorderColorHex = "#808080", // ColorConstants.GRAY equivalent
            tableCellPadding = 8f,
            datatableDefaultColumnWidthPercent = 33f,
            columnGap = 8f,
            baseFontSizePt = 12f,
        )

        /** The defaults version used for newly published template versions. */
        val CURRENT = V1

        private val REGISTRY: Map<Int, RenderingDefaults> = mapOf(
            1 to V1,
        )

        /**
         * Retrieves the [RenderingDefaults] for the given version number.
         * @throws IllegalArgumentException if the version is unknown
         */
        fun forVersion(version: Int): RenderingDefaults = REGISTRY[version] ?: throw IllegalArgumentException("Unknown rendering defaults version: $version")
    }

    /** Default heading font size for the given level. Falls back to [baseFontSizePt] for levels not in the map. */
    fun headingFontSize(level: Int): Float = headingSizes[level] ?: baseFontSizePt

    /** Default heading vertical margin for the given level. Falls back to a proportional value for unknown levels. */
    fun headingMargin(level: Int): Float = headingMargins[level] ?: (0.2f * baseFontSizePt)

    /** Component default styles for the given component type, or null if not defined. */
    fun componentDefaults(componentType: String): Map<String, Any>? = componentSpacing[componentType]
}

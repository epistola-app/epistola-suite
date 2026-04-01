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

    // -- Page header/footer --
    val pageHeaderPadding: Float = 20f,
    val pageHeaderHeight: Float = 60f,
    val pageFooterPadding: Float = 20f,
    val pageFooterHeight: Float = 60f,
) {
    /** Total vertical space reserved for the page header (padding + content height). */
    val pageHeaderReservedHeight: Float get() = pageHeaderPadding + pageHeaderHeight

    /** Total vertical space reserved for the page footer (padding + content height). */
    val pageFooterReservedHeight: Float get() = pageFooterPadding + pageFooterHeight

    companion object {
        /** iText Core version used at build time. Update when upgrading iText. */
        private const val ITEXT_VERSION = "9.5.0"

        /**
         * V1: Rendering defaults using the 4pt spacing grid (see [SpacingScale]).
         *
         * All spacing values align to multiples of [SpacingScale.DEFAULT_BASE_UNIT] (4pt).
         * Component spacing uses `sp` unit tokens. Typography sizes are in pt.
         */
        val V1 = RenderingDefaults(
            version = 1,
            defaultPageSettings = PageSettings(
                format = PageFormat.A4,
                orientation = Orientation.portrait,
                margins = Margins(top = 20, right = 20, bottom = 20, left = 20),
            ),
            componentSpacing = mapOf(
                "text" to mapOf("marginBottom" to "1.5sp"), // 6pt
                "container" to mapOf("marginBottom" to "1.5sp"),
                "columns" to mapOf("marginBottom" to "1.5sp"),
                "table" to mapOf("marginBottom" to "1.5sp"),
                "datatable" to mapOf("marginBottom" to "1.5sp"),
                "image" to mapOf("marginBottom" to "1.5sp"),
                "qrcode" to mapOf("marginBottom" to "1.5sp"),
            ),
            headingSizes = mapOf(
                1 to 24f,
                2 to 18f,
                3 to 14f,
            ),
            headingMargins = mapOf(
                1 to 12f, // 3sp
                2 to 8f, // 2sp
                3 to 4f, // 1sp
            ),
            paragraphMarginBottom = 6f, // 1.5sp
            listMarginBottom = 4f, // 1sp
            listMarginLeft = 20f, // 5sp
            listItemMarginBottom = 2f, // 0.5sp
            tableBorderWidth = 0.5f,
            tableBorderColorHex = "#808080",
            tableCellPadding = 8f, // 2sp
            datatableDefaultColumnWidthPercent = 33f,
            columnGap = 8f, // 2sp
            baseFontSizePt = 12f,
            pageHeaderPadding = 20f, // 5sp
            pageHeaderHeight = 60f,
            pageFooterPadding = 20f, // 5sp
            pageFooterHeight = 60f,
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

    /**
     * Engine version string for embedding in PDF metadata.
     * Format: `"epistola-gen-<version>+itext-<itext-version>"`.
     */
    fun engineVersionString(): String = "epistola-gen-$version+itext-$ITEXT_VERSION"
}

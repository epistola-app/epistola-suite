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

        /**
         * V2: Systematic spacing based on a 4pt grid (see [SpacingScale]).
         *
         * Changes from V1:
         * - Component spacing uses `sp()` tokens instead of em-based values
         * - Heading margins snapped to 4pt grid (was arbitrary em-derived floats)
         * - List spacing snapped to 4pt grid
         * - All values now align to multiples of [SpacingScale.DEFAULT_BASE_UNIT]
         *
         * These values must NEVER be changed — create V3 instead.
         */
        val V2 = RenderingDefaults(
            version = 2,
            defaultPageSettings = V1.defaultPageSettings, // unchanged: A4, portrait, 20mm margins
            componentSpacing = mapOf(
                "text" to mapOf("marginBottom" to "sp(1.5)"), // 6pt (was "0.5em" ≈ 6pt)
                "container" to mapOf("marginBottom" to "sp(1.5)"),
                "columns" to mapOf("marginBottom" to "sp(1.5)"),
                "table" to mapOf("marginBottom" to "sp(1.5)"),
                "datatable" to mapOf("marginBottom" to "sp(1.5)"),
                "image" to mapOf("marginBottom" to "sp(1.5)"),
            ),
            headingSizes = V1.headingSizes, // typography sizes stay in pt
            headingMargins = mapOf(
                1 to 12f, // sp(3) = 12pt (was 9.6f)
                2 to 8f, // sp(2) = 8pt (was 5.4f)
                3 to 4f, // sp(1) = 4pt (was 2.8f)
            ),
            paragraphMarginBottom = 6f, // sp(1.5) = 6pt (unchanged)
            listMarginBottom = 4f, // sp(1) = 4pt (was 3.6f)
            listMarginLeft = 20f, // sp(5) = 20pt (was 18f)
            listItemMarginBottom = 2f, // sp(0.5) = 2pt (was 1.8f)
            tableBorderWidth = 0.5f, // not spacing
            tableBorderColorHex = "#808080",
            tableCellPadding = 8f, // sp(2) = 8pt (unchanged)
            datatableDefaultColumnWidthPercent = 33f,
            columnGap = 8f, // sp(2) = 8pt (unchanged)
            baseFontSizePt = 12f,
            pageHeaderPadding = 20f, // sp(5) = 20pt (unchanged)
            pageHeaderHeight = 60f, // content dimension, not spacing
            pageFooterPadding = 20f, // sp(5) = 20pt (unchanged)
            pageFooterHeight = 60f,
        )

        /** The defaults version used for newly published template versions. */
        val CURRENT = V2

        private val REGISTRY: Map<Int, RenderingDefaults> = mapOf(
            1 to V1,
            2 to V2,
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

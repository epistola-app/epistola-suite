// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

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
    val listItemSpacingDefault: String,
    val listItemSpacingBetweenOnly: Boolean,
    val bulletMarkers: Map<String, String>,

    // -- Tables --
    val tableBorderWidth: Float,
    val tableBorderColorHex: String,
    val tableCellPadding: Float,
    val datatableDefaultColumnWidthPercent: Float,

    // -- Columns --
    val columnGap: Float,
    val columnCellPadding: Float,

    // -- Unit conversion --
    val baseFontSizePt: Float,

    // -- Page header/footer --
    val pageHeaderPadding: Float = 20f,
    val pageHeaderHeight: Float = 60f,
    val pageFooterPadding: Float = 20f,
    val pageFooterHeight: Float = 60f,

    // -- Page header/footer placement --
    /**
     * When true, page headers and footers may sit anywhere in the flow and their position picks
     * the pages they apply to, per page section (#1020). When false, the positional model:
     * at most two root-level headers (page 1, pages 2–N) and one footer on every page.
     */
    val sectionPageBands: Boolean = false,

    // -- Font selection --
    /**
     * When true, a block's font is picked once, from its merged style cascade (component
     * defaults, inherited, preset, inline), so a preset or inline style that sets only
     * `fontWeight` or `fontStyle` keeps the inherited `fontFamily` (#1030). When false, each
     * layer picks its own font, and a layer with bold or italic but no family swaps in the
     * built-in face.
     */
    val mergedFontSelection: Boolean = false,

    // -- Stencil parameters --
    /**
     * When true, a stencil parameter whose binding finds no value, or that is optional, unbound
     * and has no default, is left out of `params` rather than set to null, so `$exists(params.x)`
     * means "was given" as it does for template data (#1031). When false, every declared
     * parameter is present and `$exists` is always true.
     */
    val omitUnsetParameters: Boolean = false,

    // -- Options the registries offered but rendering ignored (#1027) --
    /** When true, the `letterSpacing` style sets the character spacing of the text it styles. */
    val applyLetterSpacing: Boolean = false,

    /** When true, `pageSettings.backgroundColor` fills every page beneath its content. */
    val pageBackground: Boolean = false,

    /** When true, a page header honours `hideOnFirstPage` the way a page footer always has. */
    val headerHideOnFirstPage: Boolean = false,

    // -- Style shorthands --
    /**
     * When true, the CSS shorthands `padding`, `margin` and `border` in inline styles and theme
     * presets expand to their per-side keys before rendering (see [StyleShorthands]). When
     * false, only per-side keys take effect and the shorthands are silently ignored.
     */
    val expandStyleShorthands: Boolean = false,

    // -- Line height --
    /**
     * When true, a `lineHeight` multiplier means what it means in CSS and in the editor: lines sit
     * `fontSize × lineHeight` apart, whatever the font (see [CssLineHeight]). When false, it scales
     * iText's multiplied leading, which is relative to the font's own ascent and descent, so the
     * same value spaces Liberation Sans 1.11× and Inter about 1.45× the font size at `1.0`.
     */
    val cssLineHeight: Boolean = false,

    /**
     * When true, a page header or footer is laid out with room below its band, so iText keeps the
     * last line of content whose glyphs are taller than its leading (a `lineHeight` below 1). The
     * band and the content's position are unchanged. When false, that line is silently dropped.
     */
    val bandOverflowRoom: Boolean = false,

    // -- Page flow --
    /**
     * When true, `keepWithNext` keeps a block with the first block that follows it, as in Word.
     * When false, it keeps it with everything the following node emits, so a heading before a
     * `loop` waits for the whole loop to fit and can leave most of a page empty.
     */
    val keepWithNextFirstBlock: Boolean = false,

    /**
     * When true, a block's vertical rhythm comes from the theme's `defaultBlockStyles` (contract
     * `2.0.0-alpha.3`): the theme's margins replace [componentSpacing]'s for the [THEME_BLOCK_TYPES],
     * and [componentSpacing] is only the fallback when the theme names none, in absolute points so a
     * larger spacing unit does not magnify it. When false, [componentSpacing] alone applies, in `sp`.
     */
    val themeBlockSpacing: Boolean = false,

    /** What this version changes, in a sentence for an author choosing to release with it. */
    val summary: String = "",
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
                "text" to mapOf("marginBottom" to "0sp"),
                "container" to mapOf("marginBottom" to "1.5sp"),
                "columns" to mapOf("marginBottom" to "1.5sp"),
                "table" to mapOf("marginBottom" to "1.5sp"),
                "datatable" to mapOf("marginBottom" to "1.5sp"),
                "image" to mapOf("marginBottom" to "1.5sp"),
                "qrcode" to mapOf("marginBottom" to "1.5sp"),
                "separator" to mapOf(
                    "marginTop" to "1.5sp",
                    "marginBottom" to "1.5sp",
                ),
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
            paragraphMarginBottom = 0f,
            listMarginBottom = 4f, // 1sp
            listMarginLeft = 20f, // 5sp
            listItemMarginBottom = 2f, // 0.5sp
            listItemSpacingDefault = "0.5sp",
            listItemSpacingBetweenOnly = false,
            bulletMarkers = mapOf(
                "disc" to "•  ",
                "circle" to "○  ",
                "square" to "■  ",
                "dash" to "–  ",
            ),
            tableBorderWidth = 0.5f,
            tableBorderColorHex = "#808080",
            tableCellPadding = 0f, // 0pt (0sp) — cells render flush; padding via per-cell styles
            datatableDefaultColumnWidthPercent = 33f,
            columnGap = 8f, // 2sp
            columnCellPadding = 2f, // iText's historical implicit cell padding
            baseFontSizePt = 12f,
            pageHeaderPadding = 20f, // 5sp
            pageHeaderHeight = 60f,
            pageFooterPadding = 20f, // 5sp
            pageFooterHeight = 60f,
        )

        /**
         * V2: list item spacing is configurable and is applied between sibling items only.
         *
         * The list bottom margin grows by the former final-item margin so the space after a
         * list remains identical to V1. All other V1 defaults remain unchanged.
         */
        val V2 = V1.copy(
            version = 2,
            listMarginBottom = 6f,
            listItemSpacingBetweenOnly = true,
        )

        /**
         * V3: column cells no longer add iText's implicit padding around their content.
         * Explicit component padding and the configured inter-column gap are unchanged.
         */
        val V3 = V2.copy(
            version = 3,
            columnCellPadding = 0f,
        )

        /** V4: page headers and footers are placed by section (#1020). */
        val V4 = V3.copy(
            version = 4,
            sectionPageBands = true,
        )

        /**
         * V5: styles the renderer ignored or mis-cascaded now take effect, and unset stencil
         * parameters are absent. See each flag: [mergedFontSelection], [omitUnsetParameters],
         * [applyLetterSpacing], [pageBackground], [headerHideOnFirstPage].
         */
        val V5 = V4.copy(
            version = 5,
            mergedFontSelection = true,
            omitUnsetParameters = true,
            applyLetterSpacing = true,
            pageBackground = true,
            headerHideOnFirstPage = true,
        )

        /**
         * V6: the `padding`, `margin` and `border` shorthands take effect ([expandStyleShorthands]),
         * `lineHeight` is CSS-like ([cssLineHeight]), and a `lineHeight` below 1 no longer drops a
         * header's or footer's last line ([bandOverflowRoom]). `keepWithNext` keeps a block with
         * the first block after it, not with a whole loop ([keepWithNextFirstBlock]).
         */
        val V6 = V5.copy(
            version = 6,
            expandStyleShorthands = true,
            cssLineHeight = true,
            bandOverflowRoom = true,
            keepWithNextFirstBlock = true,
        )

        /**
         * V7: block spacing comes from the theme ([themeBlockSpacing]). Without a theme baseline a
         * flow block keeps 6pt below it, in points: what `1.5sp` gave at the default 4pt unit, no
         * longer growing with the unit (it was 24pt at 16pt). Text keeps none, the separator its own.
         */
        val V7 = V6.copy(
            version = 7,
            themeBlockSpacing = true,
            summary = "Block spacing comes from the theme; without it a block keeps 6pt below it, whatever the spacing unit.",
            componentSpacing = mapOf(
                "text" to mapOf("marginBottom" to "0pt"),
                "container" to mapOf("marginBottom" to "6pt"),
                "columns" to mapOf("marginBottom" to "6pt"),
                "table" to mapOf("marginBottom" to "6pt"),
                "datatable" to mapOf("marginBottom" to "6pt"),
                "image" to mapOf("marginBottom" to "6pt"),
                "qrcode" to mapOf("marginBottom" to "6pt"),
                "separator" to mapOf(
                    "marginTop" to "1.5sp",
                    "marginBottom" to "1.5sp",
                ),
            ),
        )

        /** The defaults version used for newly published template versions. */
        val CURRENT = V7

        private val REGISTRY: Map<Int, RenderingDefaults> = mapOf(
            1 to V1,
            2 to V2,
            3 to V3,
            4 to V4,
            5 to V5,
            6 to V6,
            7 to V7,
        )

        /**
         * Retrieves the [RenderingDefaults] for the given version number.
         * @throws IllegalArgumentException if the version is unknown
         */
        fun forVersion(version: Int): RenderingDefaults = REGISTRY[version] ?: throw IllegalArgumentException("Unknown rendering defaults version: $version")

        /**
         * The block types whose vertical rhythm a theme's `defaultBlockStyles` sets ([themeBlockSpacing]);
         * the separator keeps its own margins (contract `catalog-compatibility.md`).
         */
        val THEME_BLOCK_TYPES: Set<String> = setOf("text", "richTextVariable", "container", "columns", "table", "datatable", "qrcode", "image")
    }

    /** Default heading font size for the given level. Falls back to [baseFontSizePt] for levels not in the map. */
    fun headingFontSize(level: Int): Float = headingSizes[level] ?: baseFontSizePt

    /** Default heading vertical margin for the given level. Falls back to a proportional value for unknown levels. */
    fun headingMargin(level: Int): Float = headingMargins[level] ?: (0.2f * baseFontSizePt)

    /** Component default styles for the given component type, or null if not defined. */
    fun componentDefaults(componentType: String): Map<String, Any>? = componentSpacing[componentType]

    /**
     * Marker glyph (plus trailing spacing) for a bullet list of the given style.
     * Falls back to the `"disc"` marker for unknown styles so rendering always produces a marker.
     */
    fun bulletMarker(style: String = "disc"): String = bulletMarkers[style] ?: bulletMarkers.getValue("disc")

    /**
     * Engine version string for embedding in PDF metadata.
     * Format: `"epistola-gen-<version>+itext-<itext-version>"`.
     */
    fun engineVersionString(): String = "epistola-gen-$version+itext-$ITEXT_VERSION"
}

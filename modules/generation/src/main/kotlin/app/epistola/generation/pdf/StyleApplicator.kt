package app.epistola.generation.pdf

import app.epistola.template.model.DocumentStyles
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.borders.DashedBorder
import com.itextpdf.layout.borders.DottedBorder
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.BlockElement
import com.itextpdf.layout.properties.BorderRadius
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue

/**
 * Applies CSS-like styles to iText elements.
 */
object StyleApplicator {

    /**
     * Style property keys that cascade from document styles to block elements.
     * Non-inheritable properties (like backgroundColor) are only applied at the document level.
     */
    val INHERITABLE_KEYS = setOf(
        "fontFamily",
        "fontSize",
        "fontWeight",
        "fontStyle",
        "color",
        "lineHeight",
        "letterSpacing",
        "textAlign",
    )

    /**
     * Applies styles from block styles and document styles to an iText element.
     */
    fun <T : BlockElement<T>> applyStyles(
        element: T,
        blockStyles: Map<String, Any>?,
        documentStyles: DocumentStyles?,
        fontCache: FontCache,
        baseFontSizePt: Float = 12f,
        spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
    ) {
        // Apply document-level styles first (as defaults)
        documentStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt, spacingUnit) }

        // Apply block-specific styles (override document styles)
        blockStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt, spacingUnit) }
    }

    /**
     * Applies styles with theme preset resolution.
     *
     * Style cascade order (lowest to highest priority):
     * 1. Component default styles (baseline for each component type)
     * 2. Inheritable document styles (typography only)
     * 3. Theme block preset (when block has stylePreset)
     * 4. Block inline styles
     *
     * @param element The iText element to style
     * @param blockInlineStyles The block's inline styles (may be null)
     * @param blockStylePreset The name of the theme preset referenced by the block (may be null)
     * @param blockStylePresets All available presets from the theme
     * @param documentStyles Document-level default styles
     * @param fontCache The font cache for font lookups
     * @param defaultStyles Component-type default styles (may be null)
     */
    fun <T : BlockElement<T>> applyStylesWithPreset(
        element: T,
        blockInlineStyles: Map<String, Any>?,
        blockStylePreset: String?,
        blockStylePresets: Map<String, Map<String, Any>>,
        documentStyles: DocumentStyles?,
        fontCache: FontCache,
        defaultStyles: Map<String, Any>? = null,
        baseFontSizePt: Float = 12f,
        spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
    ) {
        // Apply component default styles first (lowest priority)
        defaultStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt, spacingUnit) }

        // Apply only inheritable document-level styles
        documentStyles?.let { docStyles ->
            val inheritable = docStyles.filterKeys { it in INHERITABLE_KEYS }
            if (inheritable.isNotEmpty()) {
                applyBlockStyles(element, inheritable, fontCache, baseFontSizePt, spacingUnit)
            }
        }

        // Resolve preset styles (if preset exists)
        val presetStyles = blockStylePreset?.let { blockStylePresets[it] }

        // Apply preset styles (override document styles)
        presetStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt, spacingUnit) }

        // Apply block inline styles (override preset styles)
        blockInlineStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt, spacingUnit) }
    }

    /**
     * Resolves block styles by merging preset styles with inline styles.
     * Inline styles override preset styles.
     *
     * @param blockStylePresets The presets from the theme
     * @param presetName The name of the preset referenced by the block (may be null)
     * @param inlineStyles The block's inline styles (may be null)
     * @return Merged styles map with inline styles taking precedence
     */
    fun resolveBlockStyles(
        blockStylePresets: Map<String, Map<String, Any>>,
        presetName: String?,
        inlineStyles: Map<String, Any>?,
    ): Map<String, Any>? {
        val presetStyles = presetName?.let { blockStylePresets[it] }

        return when {
            presetStyles == null && inlineStyles == null -> null
            presetStyles == null -> inlineStyles
            inlineStyles == null -> presetStyles
            else -> presetStyles + inlineStyles // inline styles override preset
        }
    }

    private fun <T : BlockElement<T>> applyBlockStyles(element: T, styles: Map<String, Any>, fontCache: FontCache, baseFontSizePt: Float = 12f, spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT) {
        // Font size
        (styles["fontSize"] as? String)?.let { fontSize ->
            parseFontSize(fontSize, baseFontSizePt, spacingUnit)?.let { element.setFontSize(it) }
        }

        // Color
        (styles["color"] as? String)?.let { color ->
            parseColor(color)?.let { element.setFontColor(it) }
        }

        // Background color
        (styles["backgroundColor"] as? String)?.let { color ->
            parseColor(color)?.let { element.setBackgroundColor(it) }
        }

        // Text align
        (styles["textAlign"] as? String)?.let { align ->
            element.setTextAlignment(parseTextAlign(align))
        }

        // Margins
        (styles["marginTop"] as? String)?.let { margin ->
            parseSize(margin, baseFontSizePt, spacingUnit)?.let { element.setMarginTop(it) }
        }
        (styles["marginRight"] as? String)?.let { margin ->
            parseSize(margin, baseFontSizePt, spacingUnit)?.let { element.setMarginRight(it) }
        }
        (styles["marginBottom"] as? String)?.let { margin ->
            parseSize(margin, baseFontSizePt, spacingUnit)?.let { element.setMarginBottom(it) }
        }
        (styles["marginLeft"] as? String)?.let { margin ->
            parseSize(margin, baseFontSizePt, spacingUnit)?.let { element.setMarginLeft(it) }
        }

        // Padding
        (styles["paddingTop"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt, spacingUnit)?.let { element.setPaddingTop(it) }
        }
        (styles["paddingRight"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt, spacingUnit)?.let { element.setPaddingRight(it) }
        }
        (styles["paddingBottom"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt, spacingUnit)?.let { element.setPaddingBottom(it) }
        }
        (styles["paddingLeft"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt, spacingUnit)?.let { element.setPaddingLeft(it) }
        }

        // Width
        (styles["width"] as? String)?.let { width ->
            if (width.endsWith("%")) {
                width.removeSuffix("%").toFloatOrNull()?.let {
                    element.setWidth(UnitValue.createPercentValue(it))
                }
            } else {
                parseSize(width, baseFontSizePt, spacingUnit)?.let { element.setWidth(it) }
            }
        }

        // Font weight
        val isBold = (styles["fontWeight"] as? String)?.let { weight ->
            weight == "bold" || weight.toIntOrNull()?.let { it >= 700 } == true
        } ?: false

        // Font style
        val isItalic = (styles["fontStyle"] as? String) == "italic"

        // Apply font based on weight/style combination
        if (isBold || isItalic) {
            val font = when {
                isBold -> fontCache.bold
                else -> fontCache.italic
            }
            element.setFont(font)
        }

        // Borders: per-side shorthand (e.g., "2pt solid #2563eb")
        for ((side, setter) in BORDER_SIDE_SETTERS) {
            (styles[side] as? String)?.let { shorthand ->
                parseBorderShorthand(shorthand, baseFontSizePt, spacingUnit)?.let { border ->
                    setter(element, border)
                }
            }
        }

        // Border radius
        (styles["borderRadius"] as? String)?.let { radius ->
            parseSize(radius, baseFontSizePt, spacingUnit)?.let { element.setBorderRadius(BorderRadius(it)) }
        }

        // Line height: applied as fixed leading on Paragraph elements.
        // For Div containers, this is a no-op — line height is applied by TipTapConverter
        // on individual paragraphs where it actually takes effect.
        (styles["lineHeight"] as? Any)?.let { v ->
            val pts = parseSize(v.toString(), baseFontSizePt, spacingUnit)
            if (pts != null && element is com.itextpdf.layout.element.Paragraph) {
                element.setFixedLeading(pts)
            }
        }

        // Page flow: prevent page breaks from splitting or separating blocks
        if (styles["keepTogether"] == true || styles["keepTogether"] == "true") {
            element.setKeepTogether(true)
        }
        if (styles["keepWithNext"] == true || styles["keepWithNext"] == "true") {
            element.setKeepWithNext(true)
        }
    }

    private fun createBorder(style: String, width: Float, color: DeviceRgb): com.itextpdf.layout.borders.Border = when (style) {
        "dashed" -> DashedBorder(color, width)
        "dotted" -> DottedBorder(color, width)
        else -> SolidBorder(color, width)
    }

    /**
     * Parses a CSS border shorthand like "2pt solid #2563eb" into an iText Border.
     */
    private fun parseBorderShorthand(shorthand: String, baseFontSizePt: Float, spacingUnit: Float): com.itextpdf.layout.borders.Border? {
        val parts = shorthand.trim().split("\\s+".toRegex())
        if (parts.isEmpty()) return null

        val width = parseSize(parts[0], baseFontSizePt, spacingUnit) ?: return null
        val style = parts.getOrNull(1) ?: "solid"
        val color = parts.getOrNull(2)?.let { parseColor(it) } ?: DeviceRgb(0, 0, 0)
        if (style == "none") return null

        return createBorder(style, width, color)
    }

    @Suppress("UNCHECKED_CAST")
    private val BORDER_SIDE_SETTERS = mapOf<String, (BlockElement<*>, com.itextpdf.layout.borders.Border) -> Unit>(
        "borderTop" to { el, b -> (el as BlockElement<Any>).setBorderTop(b) },
        "borderRight" to { el, b -> (el as BlockElement<Any>).setBorderRight(b) },
        "borderBottom" to { el, b -> (el as BlockElement<Any>).setBorderBottom(b) },
        "borderLeft" to { el, b -> (el as BlockElement<Any>).setBorderLeft(b) },
    )

    private fun parseFontSize(fontSize: String, baseFontSizePt: Float = 12f, spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT): Float? {
        SpacingScale.parseSp(fontSize, spacingUnit)?.let { return it }
        return when {
            fontSize.endsWith("pt") -> fontSize.removeSuffix("pt").toFloatOrNull()
            else -> fontSize.toFloatOrNull() // unitless number treated as pt
        }
    }

    internal fun parseSize(size: String, baseFontSizePt: Float = 12f, spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT): Float? {
        // Try spacing token first (e.g., "2sp" → 8pt with default base unit)
        SpacingScale.parseSp(size, spacingUnit)?.let { return it }

        return when {
            size.endsWith("pt") -> size.removeSuffix("pt").toFloatOrNull()
            size.endsWith("mm") -> size.removeSuffix("mm").toFloatOrNull()?.let { it * 2.83465f } // page margins
            else -> size.toFloatOrNull() // unitless number treated as pt
        }
    }

    internal fun parseColor(color: String): DeviceRgb? = try {
        when {
            color.startsWith("#") -> {
                val hex = color.removePrefix("#")
                when (hex.length) {
                    6 -> {
                        val r = hex.substring(0, 2).toInt(16)
                        val g = hex.substring(2, 4).toInt(16)
                        val b = hex.substring(4, 6).toInt(16)
                        DeviceRgb(r, g, b)
                    }
                    3 -> {
                        val r = hex.substring(0, 1).repeat(2).toInt(16)
                        val g = hex.substring(1, 2).repeat(2).toInt(16)
                        val b = hex.substring(2, 3).repeat(2).toInt(16)
                        DeviceRgb(r, g, b)
                    }
                    else -> null
                }
            }
            color.startsWith("rgb(") -> {
                val values = color.removePrefix("rgb(").removeSuffix(")")
                    .split(",")
                    .map { it.trim().toInt() }
                if (values.size == 3) {
                    DeviceRgb(values[0], values[1], values[2])
                } else {
                    null
                }
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun parseTextAlign(align: String): TextAlignment = when (align.lowercase()) {
        "left" -> TextAlignment.LEFT
        "center" -> TextAlignment.CENTER
        "right" -> TextAlignment.RIGHT
        "justify" -> TextAlignment.JUSTIFIED
        else -> TextAlignment.LEFT
    }
}

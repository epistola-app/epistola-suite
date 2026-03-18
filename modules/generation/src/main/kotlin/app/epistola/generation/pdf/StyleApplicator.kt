package app.epistola.generation.pdf

import app.epistola.template.model.DefaultStyleSystem
import app.epistola.template.model.DocumentStyles
import com.itextpdf.kernel.colors.DeviceRgb
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
    val INHERITABLE_KEYS: Set<String> = DefaultStyleSystem.inheritablePropertyKeys

    /**
     * Applies styles from block styles and document styles to an iText element.
     */
    fun <T : BlockElement<T>> applyStyles(
        element: T,
        blockStyles: Map<String, Any>?,
        documentStyles: DocumentStyles?,
        fontCache: FontCache,
        baseFontSizePt: Float = 12f,
    ) {
        // Apply document-level styles first (as defaults)
        documentStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt) }

        // Apply block-specific styles (override document styles)
        blockStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt) }
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
    ) {
        // Apply component default styles first (lowest priority)
        defaultStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt) }

        // Apply only inheritable document-level styles
        documentStyles?.let { docStyles ->
            val inheritable = docStyles.filterKeys { it in INHERITABLE_KEYS }
            if (inheritable.isNotEmpty()) {
                applyBlockStyles(element, inheritable, fontCache, baseFontSizePt)
            }
        }

        // Resolve preset styles (if preset exists)
        val presetStyles = blockStylePreset?.let { blockStylePresets[it] }

        // Apply preset styles (override document styles)
        presetStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt) }

        // Apply block inline styles (override preset styles)
        blockInlineStyles?.let { applyBlockStyles(element, it, fontCache, baseFontSizePt) }
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

    private fun <T : BlockElement<T>> applyBlockStyles(element: T, styles: Map<String, Any>, fontCache: FontCache, baseFontSizePt: Float = 12f) {
        // Font size
        (styles["fontSize"] as? String)?.let { fontSize ->
            parseFontSize(fontSize, baseFontSizePt)?.let { element.setFontSize(it) }
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
            parseSize(margin, baseFontSizePt)?.let { element.setMarginTop(it) }
        }
        (styles["marginRight"] as? String)?.let { margin ->
            parseSize(margin, baseFontSizePt)?.let { element.setMarginRight(it) }
        }
        (styles["marginBottom"] as? String)?.let { margin ->
            parseSize(margin, baseFontSizePt)?.let { element.setMarginBottom(it) }
        }
        (styles["marginLeft"] as? String)?.let { margin ->
            parseSize(margin, baseFontSizePt)?.let { element.setMarginLeft(it) }
        }

        // Padding
        (styles["paddingTop"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt)?.let { element.setPaddingTop(it) }
        }
        (styles["paddingRight"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt)?.let { element.setPaddingRight(it) }
        }
        (styles["paddingBottom"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt)?.let { element.setPaddingBottom(it) }
        }
        (styles["paddingLeft"] as? String)?.let { padding ->
            parseSize(padding, baseFontSizePt)?.let { element.setPaddingLeft(it) }
        }

        // Borders
        applyBorder(element, "Top", styles, baseFontSizePt)
        applyBorder(element, "Right", styles, baseFontSizePt)
        applyBorder(element, "Bottom", styles, baseFontSizePt)
        applyBorder(element, "Left", styles, baseFontSizePt)

        // Border radius
        applyBorderRadius(element, "TopLeft", styles, baseFontSizePt)
        applyBorderRadius(element, "TopRight", styles, baseFontSizePt)
        applyBorderRadius(element, "BottomRight", styles, baseFontSizePt)
        applyBorderRadius(element, "BottomLeft", styles, baseFontSizePt)

        // Width
        (styles["width"] as? String)?.let { width ->
            if (width.endsWith("%")) {
                width.removeSuffix("%").toFloatOrNull()?.let {
                    element.setWidth(UnitValue.createPercentValue(it))
                }
            } else {
                parseSize(width, baseFontSizePt)?.let { element.setWidth(it) }
            }
        }

        // Letter spacing - applied to BlockElement (supported via setCharacterSpacing from ElementPropertyContainer)
        (styles["letterSpacing"] as? String)?.let { letterSpacing ->
            parseSize(letterSpacing, baseFontSizePt)?.let { element.setCharacterSpacing(it) }
        }

        // Note: lineHeight is not applied here because it's only available on Paragraph elements,
        // not generic BlockElement. Our architecture creates Div containers first, then adds
        // Paragraphs as children during TipTap conversion. To support lineHeight in PDF, we would
        // need to pass style values through to the TipTapConverter and apply setMultipliedLeading()
        // when creating Paragraph elements. For now, lineHeight works in browser preview only.

        // Font family, weight, and style - applied together as a single font selection
        val fontFamily = parseFontFamily(styles["fontFamily"] as? String)
        val fontWeight = (styles["fontWeight"] as? String)?.toIntOrNull() ?: 400
        val isItalic = (styles["fontStyle"] as? String) == "italic"

        // Apply the font based on family, weight (>= 500 is bold), and style
        val font = fontCache.getFont(family = fontFamily, weight = fontWeight, italic = isItalic)
        element.setFont(font)
    }

    private fun parseFontSize(fontSize: String, baseFontSizePt: Float = 12f): Float? = when {
        fontSize.endsWith("px") -> fontSize.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f } // px to pt
        fontSize.endsWith("pt") -> fontSize.removeSuffix("pt").toFloatOrNull()
        fontSize.endsWith("em") -> fontSize.removeSuffix("em").toFloatOrNull()?.let { it * baseFontSizePt }
        fontSize.endsWith("rem") -> fontSize.removeSuffix("rem").toFloatOrNull()?.let { it * baseFontSizePt }
        else -> fontSize.toFloatOrNull()
    }

    private fun parseLineHeight(lineHeight: String, baseFontSizePt: Float = 12f): Float? = when {
        // Unitless values like "1.5" are multipliers
        lineHeight.toFloatOrNull() != null -> lineHeight.toFloat()
        // Values with units need conversion
        lineHeight.endsWith("px") -> lineHeight.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f / baseFontSizePt }
        lineHeight.endsWith("pt") -> lineHeight.removeSuffix("pt").toFloatOrNull()?.let { it / baseFontSizePt }
        lineHeight.endsWith("em") -> lineHeight.removeSuffix("em").toFloatOrNull()
        lineHeight.endsWith("rem") -> lineHeight.removeSuffix("rem").toFloatOrNull()
        else -> lineHeight.toFloatOrNull()
    }

    private fun parseSize(size: String, baseFontSizePt: Float = 12f): Float? = when {
        size.endsWith("px") -> size.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f }
        size.endsWith("pt") -> size.removeSuffix("pt").toFloatOrNull()
        size.endsWith("mm") -> size.removeSuffix("mm").toFloatOrNull()?.let { it * 2.83465f } // mm to pt
        size.endsWith("cm") -> size.removeSuffix("cm").toFloatOrNull()?.let { it * 28.3465f }
        size.endsWith("em") -> size.removeSuffix("em").toFloatOrNull()?.let { it * baseFontSizePt }
        else -> size.toFloatOrNull()
    }

    private fun parseFontFamily(fontFamily: String?): FontFamily = when (fontFamily) {
        "Liberation Serif" -> FontFamily.SERIF
        "Liberation Mono" -> FontFamily.MONO
        else -> FontFamily.SANS // Default to Sans
    }

    private fun parseColor(color: String): DeviceRgb? = try {
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

    /**
     * Applies border to a specific side of the element.
     * All three properties (width, style, color) must be present for the border to render.
     */
    private fun <T : BlockElement<T>> applyBorder(
        element: T,
        side: String,
        styles: Map<String, Any>,
        baseFontSizePt: Float = 12f,
    ) {
        val widthKey = "border${side}Width"
        val styleKey = "border${side}Style"
        val colorKey = "border${side}Color"

        val width = (styles[widthKey] as? String)?.let { parseSize(it, baseFontSizePt) }
        val style = styles[styleKey] as? String
        val color = (styles[colorKey] as? String)?.let { parseColor(it) }

        // Only apply border if all three properties are present
        if (width == null || style == null || color == null) return
        if (width <= 0 || style == "none") return

        val border = createBorder(style, width, color) ?: return

        when (side) {
            "Top" -> element.setBorderTop(border)
            "Right" -> element.setBorderRight(border)
            "Bottom" -> element.setBorderBottom(border)
            "Left" -> element.setBorderLeft(border)
        }
    }

    /**
     * Creates an iText Border instance based on the style string.
     */
    private fun createBorder(style: String, width: Float, color: DeviceRgb): com.itextpdf.layout.borders.Border? = when (style.lowercase()) {
        "solid" -> com.itextpdf.layout.borders.SolidBorder(color, width)
        "dashed" -> com.itextpdf.layout.borders.DashedBorder(color, width)
        "dotted" -> com.itextpdf.layout.borders.DottedBorder(color, width)
        "double" -> com.itextpdf.layout.borders.DoubleBorder(color, width)
        else -> com.itextpdf.layout.borders.SolidBorder(color, width)
    }

    /**
     * Applies border radius to a specific corner of the element.
     */
    private fun <T : BlockElement<T>> applyBorderRadius(
        element: T,
        corner: String,
        styles: Map<String, Any>,
        baseFontSizePt: Float = 12f,
    ) {
        val radiusKey = "border${corner}Radius"

        val radius = (styles[radiusKey] as? String)?.let { parseSize(it, baseFontSizePt) }

        // Only apply radius if value is present and positive
        if (radius == null || radius <= 0) return

        val borderRadius = BorderRadius(radius)

        when (corner) {
            "TopLeft" -> element.setBorderTopLeftRadius(borderRadius)
            "TopRight" -> element.setBorderTopRightRadius(borderRadius)
            "BottomRight" -> element.setBorderBottomRightRadius(borderRadius)
            "BottomLeft" -> element.setBorderBottomLeftRadius(borderRadius)
        }
    }
}

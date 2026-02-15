package app.epistola.generation.pdf

import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.TextAlign
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.DashedBorder
import com.itextpdf.layout.borders.DottedBorder
import com.itextpdf.layout.borders.DoubleBorder
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.element.BlockElement
import com.itextpdf.layout.properties.BorderRadius
import com.itextpdf.layout.properties.LineHeight
import com.itextpdf.layout.properties.OverflowPropertyValue
import com.itextpdf.layout.properties.OverflowWrapPropertyValue
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue

/**
 * Applies CSS-like styles to iText elements.
 */
object StyleApplicator {
    private val inheritableKeys = setOf(
        "fontFamily",
        "fontSize",
        "fontWeight",
        "color",
        "lineHeight",
        "letterSpacing",
        "textAlign",
        "backgroundColor",
    )

    /**
     * Backward-compatible font-weight handling.
     * Supports CSS keywords and numeric values.
     */
    fun isBoldFontWeight(weight: String): Boolean {
        val normalized = weight.trim().lowercase()
        return when (normalized) {
            "bold", "bolder" -> true
            "normal", "lighter" -> false
            else -> normalized.toIntOrNull()?.let { it >= 700 } ?: false
        }
    }

    /**
     * Applies styles from block styles and document styles to an iText element.
     */
    fun <T : BlockElement<T>> applyStyles(
        element: T,
        blockStyles: Map<String, Any>?,
        documentStyles: DocumentStyles?,
        fontCache: FontCache,
    ) {
        // Apply document-level styles first (as defaults)
        documentStyles?.let { applyDocumentStyles(element, it, fontCache) }

        // Apply block-specific styles (override document styles)
        blockStyles?.let { applyBlockStyles(element, it, fontCache) }
    }

    /**
     * Applies styles with theme preset resolution.
     *
     * Style cascade order (lowest to highest priority):
     * 1. Document styles (from template or theme)
     * 2. Theme block preset (when block has stylePreset)
     * 3. Block inline styles
     *
     * @param element The iText element to style
     * @param blockInlineStyles The block's inline styles (may be null)
     * @param blockStylePreset The name of the theme preset referenced by the block (may be null)
     * @param blockStylePresets All available presets from the theme
     * @param documentStyles Document-level default styles
     * @param fontCache The font cache for font lookups
     */
    fun <T : BlockElement<T>> applyStylesWithPreset(
        element: T,
        blockInlineStyles: Map<String, Any>?,
        blockStylePreset: String?,
        blockStylePresets: Map<String, Map<String, Any>>,
        documentStyles: DocumentStyles?,
        fontCache: FontCache,
    ) {
        // Apply document-level styles first (as defaults)
        documentStyles?.let { applyDocumentStyles(element, it, fontCache) }

        // Resolve preset styles (if preset exists)
        val presetStyles = blockStylePreset?.let { blockStylePresets[it] }

        // Apply preset styles (override document styles)
        presetStyles?.let { applyBlockStyles(element, it, fontCache) }

        // Apply block inline styles (override preset styles)
        blockInlineStyles?.let { applyBlockStyles(element, it, fontCache) }
    }

    /**
     * Converts document styles into inheritable style map.
     */
    fun documentStylesToInheritedMap(documentStyles: DocumentStyles?): Map<String, Any> {
        if (documentStyles == null) return emptyMap()

        val styles = mutableMapOf<String, Any>()
        documentStyles.fontFamily?.let { styles["fontFamily"] = it }
        documentStyles.fontSize?.let { styles["fontSize"] = it }
        documentStyles.fontWeight?.let { styles["fontWeight"] = it }
        documentStyles.color?.let { styles["color"] = it }
        documentStyles.lineHeight?.let { styles["lineHeight"] = it }
        documentStyles.letterSpacing?.let { styles["letterSpacing"] = it }
        documentStyles.textAlign?.let { styles["textAlign"] = it.name.lowercase() }
        documentStyles.backgroundColor?.let { styles["backgroundColor"] = it }
        return styles
    }

    /**
     * Resolves inherited styles in hierarchical order:
     * parent inherited -> preset -> inline.
     * Only inheritable keys are carried forward.
     */
    fun resolveInheritedStyles(
        parentInheritedStyles: Map<String, Any>,
        presetName: String?,
        blockStylePresets: Map<String, Map<String, Any>>,
        inlineStyles: Map<String, Any>?,
    ): Map<String, Any> {
        val resolved = parentInheritedStyles.toMutableMap()

        val presetStyles = presetName?.let { blockStylePresets[it] }
        if (presetStyles != null) {
            for ((key, value) in presetStyles) {
                if (key in inheritableKeys) {
                    resolved[key] = normalizeStyleValue(key, value)
                }
            }
        }

        if (inlineStyles != null) {
            for ((key, value) in inlineStyles) {
                if (key in inheritableKeys) {
                    resolved[key] = normalizeStyleValue(key, value)
                }
            }
        }

        return resolved
    }

    /**
     * Applies pre-resolved style map to an element.
     */
    fun <T : BlockElement<T>> applyResolvedStyles(
        element: T,
        resolvedStyles: Map<String, Any>,
        fontCache: FontCache,
    ) {
        applyBlockStyles(element, resolvedStyles, fontCache)
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

    /**
     * Resolves styles to apply on the current rendered element.
     *
     * Includes inherited parent styles plus full block-level styles
     * (both inheritable and non-inheritable properties).
     */
    fun resolveElementStyles(
        parentInheritedStyles: Map<String, Any>,
        presetName: String?,
        blockStylePresets: Map<String, Map<String, Any>>,
        inlineStyles: Map<String, Any>?,
    ): Map<String, Any> {
        val blockStyles = resolveBlockStyles(blockStylePresets, presetName, inlineStyles) ?: emptyMap()
        return parentInheritedStyles + blockStyles
    }

    private fun <T : BlockElement<T>> applyDocumentStyles(element: T, styles: DocumentStyles, fontCache: FontCache) {
        applyBlockStyles(element, documentStylesToInheritedMap(styles), fontCache)
    }

    private fun <T : BlockElement<T>> applyBlockStyles(element: T, styles: Map<String, Any>, fontCache: FontCache) {
        val fontFamily = styles["fontFamily"] as? String
        val fontWeight = styles["fontWeight"] as? String
        val fontStyle = styles["fontStyle"] as? String

        applyFontSelection(element, fontFamily, fontWeight, fontStyle, fontCache)

        // Font size
        (styles["fontSize"] as? String)?.let { fontSize ->
            parseFontSize(fontSize)?.let { element.setFontSize(it) }
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

        // Line height
        (styles["lineHeight"] as? String)?.let { lineHeight ->
            parseLineHeight(lineHeight)?.let { element.setProperty(Property.LINE_HEIGHT, it) }
        }

        // Letter spacing
        (styles["letterSpacing"] as? String)?.let { spacing ->
            parseSize(spacing)?.let { element.setCharacterSpacing(it) }
        }

        // Margins
        (styles["marginTop"] as? String)?.let { margin ->
            parseSize(margin)?.let { element.setMarginTop(it) }
        }
        (styles["marginRight"] as? String)?.let { margin ->
            parseSize(margin)?.let { element.setMarginRight(it) }
        }
        (styles["marginBottom"] as? String)?.let { margin ->
            parseSize(margin)?.let { element.setMarginBottom(it) }
        }
        (styles["marginLeft"] as? String)?.let { margin ->
            parseSize(margin)?.let { element.setMarginLeft(it) }
        }

        // Padding
        (styles["padding"] as? String)?.let { padding ->
            parseBoxSpacing(padding)?.let {
                element.setPaddingTop(it.top)
                element.setPaddingRight(it.right)
                element.setPaddingBottom(it.bottom)
                element.setPaddingLeft(it.left)
            }
        }
        (styles["paddingTop"] as? String)?.let { padding ->
            parseSize(padding)?.let { element.setPaddingTop(it) }
        }
        (styles["paddingRight"] as? String)?.let { padding ->
            parseSize(padding)?.let { element.setPaddingRight(it) }
        }
        (styles["paddingBottom"] as? String)?.let { padding ->
            parseSize(padding)?.let { element.setPaddingBottom(it) }
        }
        (styles["paddingLeft"] as? String)?.let { padding ->
            parseSize(padding)?.let { element.setPaddingLeft(it) }
        }

        // Width / Height constraints
        (styles["width"] as? String)?.let { width ->
            parseLength(width)?.let { element.setWidth(it) }
        }
        (styles["minWidth"] as? String)?.let { minWidth ->
            parseLength(minWidth)?.let { element.setMinWidth(it) }
        }
        (styles["maxWidth"] as? String)?.let { maxWidth ->
            parseLength(maxWidth)?.let { element.setMaxWidth(it) }
        }
        (styles["height"] as? String)?.let { height ->
            parseLength(height)?.let { element.setHeight(it) }
        }
        (styles["minHeight"] as? String)?.let { minHeight ->
            parseLength(minHeight)?.let { element.setMinHeight(it) }
        }
        (styles["maxHeight"] as? String)?.let { maxHeight ->
            parseLength(maxHeight)?.let { element.setMaxHeight(it) }
        }

        // Opacity
        (styles["opacity"] as? String)?.let { opacity ->
            parseOpacity(opacity)?.let { element.setOpacity(it) }
        }

        // Border
        applyBorderStyles(element, styles)

        // Text decoration
        (styles["textDecoration"] as? String)?.let { decoration ->
            val normalized = decoration.trim().lowercase()
            if (normalized == "none") {
                element.deleteOwnProperty(Property.UNDERLINE)
            } else {
                if (normalized.contains("underline")) element.setUnderline()
                if (normalized.contains("line-through")) element.setLineThrough()
            }
        }

        // White space
        (styles["whiteSpace"] as? String)?.let { whiteSpace ->
            val disableSoftWrap = whiteSpace.trim().lowercase() == "nowrap"
            element.setProperty(Property.NO_SOFT_WRAP_INLINE, disableSoftWrap)
        }

        // Word break / overflow wrap
        (styles["wordBreak"] as? String)?.let { wordBreak ->
            parseOverflowWrap(wordBreak)?.let { element.setProperty(Property.OVERFLOW_WRAP, it) }
        }

        // Overflow
        (styles["overflow"] as? String)?.let { overflow ->
            parseOverflow(overflow)?.let {
                element.setProperty(Property.OVERFLOW_X, it)
                element.setProperty(Property.OVERFLOW_Y, it)
            }
        }
        (styles["overflowX"] as? String)?.let { overflowX ->
            parseOverflow(overflowX)?.let { element.setProperty(Property.OVERFLOW_X, it) }
        }
        (styles["overflowY"] as? String)?.let { overflowY ->
            parseOverflow(overflowY)?.let { element.setProperty(Property.OVERFLOW_Y, it) }
        }
    }

    private fun <T : BlockElement<T>> applyFontSelection(
        element: T,
        fontFamily: String?,
        fontWeight: String?,
        fontStyle: String?,
        fontCache: FontCache,
    ) {
        val hasFontIntent = !fontFamily.isNullOrBlank() || !fontWeight.isNullOrBlank() || !fontStyle.isNullOrBlank()
        if (!hasFontIntent) return

        val isBold = fontWeight?.let { isBoldFontWeight(it) } ?: false
        val isItalic = fontStyle?.trim()?.lowercase()?.let { it == "italic" || it == "oblique" } ?: false
        fontCache.resolveFont(fontFamily, isBold, isItalic)?.let { element.setFont(it) }
    }

    private fun <T : BlockElement<T>> applyBorderStyles(element: T, styles: Map<String, Any>) {
        val borderStyle = (styles["borderStyle"] as? String)?.trim()?.lowercase()
        val borderWidth = (styles["borderWidth"] as? String)?.let { parseSize(it) }
        val borderColor = (styles["borderColor"] as? String)?.let { parseColor(it) }
        val borderRadius = (styles["borderRadius"] as? String)?.let { parseSize(it) }

        borderRadius?.let { element.setBorderRadius(BorderRadius(it)) }

        val hasBorderConfig = borderStyle != null || borderWidth != null || borderColor != null
        if (hasBorderConfig) {
            if (borderStyle == "none") {
                element.setBorder(Border.NO_BORDER)
            } else {
                val width = borderWidth ?: 0.75f
                val color = borderColor ?: ColorConstants.BLACK
                val style = borderStyle ?: "solid"
                createBorder(style, width, color)?.let { element.setBorder(it) }
            }
        }

        (styles["border"] as? String)?.let { border ->
            parseBorderShorthand(border)?.let { element.setBorder(it) }
        }
        (styles["borderTop"] as? String)?.let { borderTop ->
            parseBorderShorthand(borderTop)?.let { element.setBorderTop(it) }
        }
        (styles["borderRight"] as? String)?.let { borderRight ->
            parseBorderShorthand(borderRight)?.let { element.setBorderRight(it) }
        }
        (styles["borderBottom"] as? String)?.let { borderBottom ->
            parseBorderShorthand(borderBottom)?.let { element.setBorderBottom(it) }
        }
        (styles["borderLeft"] as? String)?.let { borderLeft ->
            parseBorderShorthand(borderLeft)?.let { element.setBorderLeft(it) }
        }
    }

    private data class BoxSpacing(
        val top: Float,
        val right: Float,
        val bottom: Float,
        val left: Float,
    )

    private fun parseBoxSpacing(value: String): BoxSpacing? {
        val tokens = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens.size > 4) return null

        val parsed = tokens.map { parseSize(it) ?: return null }
        return when (parsed.size) {
            1 -> BoxSpacing(parsed[0], parsed[0], parsed[0], parsed[0])
            2 -> BoxSpacing(parsed[0], parsed[1], parsed[0], parsed[1])
            3 -> BoxSpacing(parsed[0], parsed[1], parsed[2], parsed[1])
            4 -> BoxSpacing(parsed[0], parsed[1], parsed[2], parsed[3])
            else -> null
        }
    }

    private fun parseBorderShorthand(value: String): Border? {
        val normalized = value.trim()
        if (normalized.isEmpty()) return null

        val keyword = normalized.lowercase()
        if (keyword == "none") return Border.NO_BORDER

        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.size != 3) return null

        val width = parseSize(tokens[0]) ?: return null
        val style = tokens[1].lowercase()
        val color = parseColor(tokens[2]) ?: return null
        return createBorder(style, width, color)
    }

    private fun createBorder(style: String, width: Float, color: Color): Border? = when (style) {
        "solid" -> SolidBorder(color, width)
        "dashed" -> DashedBorder(color, width)
        "dotted" -> DottedBorder(color, width)
        "double" -> DoubleBorder(color, width)
        else -> null
    }

    private fun parseLineHeight(value: String): LineHeight? {
        val normalized = value.trim().lowercase()
        if (normalized.isEmpty()) return null
        if (normalized == "normal") return LineHeight.createNormalValue()

        return if (hasCssUnit(normalized)) {
            parseSize(normalized)?.let { LineHeight.createFixedValue(it) }
        } else {
            normalized.toFloatOrNull()?.let { LineHeight.createMultipliedValue(it) }
        }
    }

    private fun parseLength(value: String): UnitValue? {
        val normalized = value.trim().lowercase()
        if (normalized.endsWith("%")) {
            return normalized.removeSuffix("%").toFloatOrNull()?.let { UnitValue.createPercentValue(it) }
        }
        return parseSize(normalized)?.let { UnitValue.createPointValue(it) }
    }

    private fun parseOpacity(value: String): Float? {
        val normalized = value.trim().lowercase()
        val opacity = if (normalized.endsWith("%")) {
            normalized.removeSuffix("%").toFloatOrNull()?.div(100f)
        } else {
            normalized.toFloatOrNull()
        }
        return opacity?.coerceIn(0f, 1f)
    }

    private fun parseOverflow(value: String): OverflowPropertyValue? = when (value.trim().lowercase()) {
        "visible" -> OverflowPropertyValue.VISIBLE
        "hidden" -> OverflowPropertyValue.HIDDEN
        "fit" -> OverflowPropertyValue.FIT
        else -> null
    }

    private fun parseOverflowWrap(value: String): OverflowWrapPropertyValue? = when (value.trim().lowercase()) {
        "normal" -> OverflowWrapPropertyValue.NORMAL
        "break-word" -> OverflowWrapPropertyValue.BREAK_WORD
        "break-all" -> OverflowWrapPropertyValue.ANYWHERE
        else -> null
    }

    private fun hasCssUnit(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized.endsWith("px") ||
            normalized.endsWith("pt") ||
            normalized.endsWith("mm") ||
            normalized.endsWith("cm") ||
            normalized.endsWith("rem") ||
            normalized.endsWith("em") ||
            normalized.endsWith("%")
    }

    private fun normalizeStyleValue(key: String, value: Any): Any {
        if (key == "textAlign") {
            return when (value) {
                is TextAlign -> value.name.lowercase()
                else -> value.toString().lowercase()
            }
        }
        return value
    }

    private fun parseFontSize(fontSize: String): Float? {
        val normalized = fontSize.trim().lowercase()
        return when {
            normalized.endsWith("px") -> normalized.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f } // px to pt
            normalized.endsWith("pt") -> normalized.removeSuffix("pt").toFloatOrNull()
            normalized.endsWith("rem") -> normalized.removeSuffix("rem").toFloatOrNull()?.let { it * 12f }
            normalized.endsWith("em") -> normalized.removeSuffix("em").toFloatOrNull()?.let { it * 12f } // 1em = 12pt
            else -> normalized.toFloatOrNull()
        }
    }

    private fun parseSize(size: String): Float? {
        val normalized = size.trim().lowercase()
        return when {
            normalized.endsWith("px") -> normalized.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f }
            normalized.endsWith("pt") -> normalized.removeSuffix("pt").toFloatOrNull()
            normalized.endsWith("mm") -> normalized.removeSuffix("mm").toFloatOrNull()?.let { it * 2.83465f } // mm to pt
            normalized.endsWith("cm") -> normalized.removeSuffix("cm").toFloatOrNull()?.let { it * 28.3465f }
            normalized.endsWith("rem") -> normalized.removeSuffix("rem").toFloatOrNull()?.let { it * 12f }
            normalized.endsWith("em") -> normalized.removeSuffix("em").toFloatOrNull()?.let { it * 12f }
            else -> normalized.toFloatOrNull()
        }
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

}

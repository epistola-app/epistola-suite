package app.epistola.generation.pdf

import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.TextAlign
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.element.BlockElement
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue

/**
 * Applies CSS-like styles to iText elements.
 */
object StyleApplicator {
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

    private fun <T : BlockElement<T>> applyDocumentStyles(element: T, styles: DocumentStyles, fontCache: FontCache) {
        styles.fontFamily?.let { fontFamily ->
            // iText uses font programs, we'll use a default font
            // Custom font handling would require font registration
        }

        styles.fontSize?.let { fontSize ->
            parseFontSize(fontSize)?.let { element.setFontSize(it) }
        }

        styles.color?.let { color ->
            parseColor(color)?.let { element.setFontColor(it) }
        }

        styles.backgroundColor?.let { color ->
            parseColor(color)?.let { element.setBackgroundColor(it) }
        }

        styles.textAlign?.let { align ->
            element.setTextAlignment(mapTextAlign(align))
        }

        // Font weight (numeric values: 100-900)
        styles.fontWeight?.let { weight ->
            val numericWeight = weight.toIntOrNull() ?: 400
            if (numericWeight >= 700) {
                element.setFont(fontCache.bold)
            }
        }

        // Letter spacing
        styles.letterSpacing?.let { spacing ->
            parseSize(spacing)?.let { element.setCharacterSpacing(it) }
        }

        // Note: lineHeight is handled differently in iText - skipping for now
        // styles.lineHeight would require setFixedLeading which needs a specific point value
    }

    private fun <T : BlockElement<T>> applyBlockStyles(element: T, styles: Map<String, Any>, fontCache: FontCache) {
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

        // Width
        (styles["width"] as? String)?.let { width ->
            if (width.endsWith("%")) {
                width.removeSuffix("%").toFloatOrNull()?.let {
                    element.setWidth(UnitValue.createPercentValue(it))
                }
            } else {
                parseSize(width)?.let { element.setWidth(it) }
            }
        }

        // Font weight (numeric values: 100-900, >= 700 is bold)
        (styles["fontWeight"] as? String)?.let { weight ->
            val numericWeight = weight.toIntOrNull() ?: 400
            if (numericWeight >= 700) {
                element.setFont(fontCache.bold)
            }
        }

        // Font style
        (styles["fontStyle"] as? String)?.let { style ->
            if (style == "italic") {
                element.setFont(fontCache.italic)
            }
        }

        // Note: lineHeight is handled differently in iText - skipping for now
    }

    private fun parseFontSize(fontSize: String): Float? = when {
        fontSize.endsWith("px") -> fontSize.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f } // px to pt
        fontSize.endsWith("pt") -> fontSize.removeSuffix("pt").toFloatOrNull()
        fontSize.endsWith("em") -> fontSize.removeSuffix("em").toFloatOrNull()?.let { it * 12f } // 1em = 12pt
        fontSize.endsWith("rem") -> fontSize.removeSuffix("rem").toFloatOrNull()?.let { it * 12f }
        else -> fontSize.toFloatOrNull()
    }

    private fun parseSize(size: String): Float? = when {
        size.endsWith("px") -> size.removeSuffix("px").toFloatOrNull()?.let { it * 0.75f }
        size.endsWith("pt") -> size.removeSuffix("pt").toFloatOrNull()
        size.endsWith("mm") -> size.removeSuffix("mm").toFloatOrNull()?.let { it * 2.83465f } // mm to pt
        size.endsWith("cm") -> size.removeSuffix("cm").toFloatOrNull()?.let { it * 28.3465f }
        size.endsWith("em") -> size.removeSuffix("em").toFloatOrNull()?.let { it * 12f }
        else -> size.toFloatOrNull()
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

    private fun mapTextAlign(align: TextAlign): TextAlignment = when (align) {
        TextAlign.Left -> TextAlignment.LEFT
        TextAlign.Center -> TextAlignment.CENTER
        TextAlign.Right -> TextAlignment.RIGHT
        TextAlign.Justify -> TextAlignment.JUSTIFIED
    }
}

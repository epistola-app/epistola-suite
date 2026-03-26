package app.epistola.generation.pdf

import app.epistola.template.model.BorderStyle
import app.epistola.template.model.Expression
import app.epistola.template.model.ExpressionLanguage
import app.epistola.template.model.Node
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Cell

/**
 * Extracts an [Expression] from a node prop value.
 *
 * In the v2 node/slot model, Expression objects are stored as Maps in [Node.props].
 * This function handles the conversion from the raw Map representation back to an [Expression].
 *
 * @param prop The raw prop value (expected to be a Map with "raw" and optional "language" keys)
 * @param defaultLanguage The default expression language to use when "language" is not specified
 * @return The extracted [Expression], or null if the prop is not a valid expression map
 */
internal fun extractExpression(prop: Any?, defaultLanguage: ExpressionLanguage): Expression? {
    if (prop == null) return null

    @Suppress("UNCHECKED_CAST")
    val expressionMap = prop as? Map<String, Any?> ?: return null

    val raw = expressionMap["raw"] as? String ?: return null
    val language = (expressionMap["language"] as? String)?.let { langStr ->
        try {
            ExpressionLanguage.valueOf(langStr)
        } catch (_: IllegalArgumentException) {
            defaultLanguage
        }
    } ?: defaultLanguage

    return Expression(raw = raw, language = language)
}

/**
 * Filters out null values from a style map.
 *
 * The v2 [Node.styles] is typed as `Map<String, Any?>?` but [StyleApplicator] expects
 * `Map<String, Any>?`. Null values have no meaning in the style cascade, so we filter them out.
 */
internal fun Map<String, Any?>.filterNonNullValues(): Map<String, Any> {
    @Suppress("UNCHECKED_CAST")
    return this.filterValues { it != null } as Map<String, Any>
}

/**
 * Parses a border style string prop into a [BorderStyle] enum value.
 * Returns [BorderStyle.all] if the value is null or unrecognized.
 */
internal fun parseBorderStyle(borderStyleStr: String?): BorderStyle = borderStyleStr?.let {
    try {
        BorderStyle.valueOf(it)
    } catch (_: IllegalArgumentException) {
        BorderStyle.all
    }
} ?: BorderStyle.all

/**
 * Applies border styling to an iText [Cell] based on the given [BorderStyle].
 * Shared between [TableNodeRenderer] and [DatatableNodeRenderer].
 */
internal fun applyCellBorder(
    cell: Cell,
    borderStyle: BorderStyle,
    borderColor: com.itextpdf.kernel.colors.Color,
    borderWidth: Float,
) {
    val solidBorder = SolidBorder(borderColor, borderWidth)

    when (borderStyle) {
        BorderStyle.all -> {
            cell.setBorder(solidBorder)
        }
        BorderStyle.horizontal -> {
            cell.setBorderTop(solidBorder)
            cell.setBorderBottom(solidBorder)
            cell.setBorderLeft(Border.NO_BORDER)
            cell.setBorderRight(Border.NO_BORDER)
        }
        BorderStyle.vertical -> {
            cell.setBorderTop(Border.NO_BORDER)
            cell.setBorderBottom(Border.NO_BORDER)
            cell.setBorderLeft(solidBorder)
            cell.setBorderRight(solidBorder)
        }
        BorderStyle.none -> {
            cell.setBorder(Border.NO_BORDER)
        }
    }
}

/**
 * Parses a hex color string (e.g. "#808080") into an iText [Color].
 * Returns [ColorConstants.GRAY] if parsing fails.
 */
internal fun parseHexBorderColor(hex: String): Color = try {
    val clean = hex.removePrefix("#")
    if (clean.length == 6) {
        DeviceRgb(
            clean.substring(0, 2).toInt(16),
            clean.substring(2, 4).toInt(16),
            clean.substring(4, 6).toInt(16),
        )
    } else {
        ColorConstants.GRAY
    }
} catch (_: Exception) {
    ColorConstants.GRAY
}

/**
 * Parses a node's `height` prop as a size value (pt or sp) to points.
 * Returns null if no height prop is set or if it can't be parsed.
 */
internal fun parseNodeHeight(node: Node?, context: RenderContext): Float? {
    val value = node?.props?.get("height") ?: return null
    return when (value) {
        is Number -> value.toFloat()
        is String -> {
            SpacingScale.parseSp(value, context.spacingUnit)?.let { return it }
            when {
                value.endsWith("pt") -> value.removeSuffix("pt").toFloatOrNull()
                else -> value.toFloatOrNull()
            }
        }
        else -> null
    }
}

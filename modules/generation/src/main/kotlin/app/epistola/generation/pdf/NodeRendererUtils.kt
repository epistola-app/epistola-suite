package app.epistola.generation.pdf

import app.epistola.template.model.BorderStyle
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Cell

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

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.layout.borders.DashedBorder
import com.itextpdf.layout.borders.DottedBorder
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.properties.HorizontalAlignment
import com.itextpdf.layout.properties.UnitValue

/**
 * Renders a "separator" node as a centered horizontal line.
 *
 * Props:
 * - `thickness`: line thickness (e.g. "1pt", default 1pt)
 * - `width`: line width as percentage (e.g. "100%", default 100%)
 * - `color`: line color (e.g. "#d1d5db", default gray)
 * - `style`: line style ("solid", "dashed", "dotted", default solid)
 */
class SeparatorNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val props = node.props ?: emptyMap()
        val thickness = parseThickness(props["thickness"] as? String)
        val widthPercent = parseWidthPercent(props["width"] as? String)
        val color = parseColor(props["color"] as? String)
        val lineStyle = props["style"] as? String ?: "solid"

        val border = when (lineStyle) {
            "dashed" -> DashedBorder(color, thickness)
            "dotted" -> DottedBorder(color, thickness)
            else -> SolidBorder(color, thickness)
        }

        // Inner div is the actual line
        val line = Div()
        line.setWidth(UnitValue.createPercentValue(widthPercent))
        line.setHeight(0f)
        line.setBorderTop(border)
        line.setHorizontalAlignment(HorizontalAlignment.CENTER)

        // Outer div carries margins from styles
        val wrapper = Div()
        StyleApplicator.applyStylesWithPreset(
            wrapper,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("separator"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )
        wrapper.add(line)
        wrapper.setHorizontalAlignment(HorizontalAlignment.CENTER)

        return listOf(wrapper)
    }

    private fun parseThickness(value: String?): Float {
        if (value == null) return 1f
        val match = Regex("""([\d.]+)""").find(value)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 1f
    }

    private fun parseWidthPercent(value: String?): Float {
        if (value == null) return 100f
        val match = Regex("""([\d.]+)%?""").find(value)
        return match?.groupValues?.get(1)?.toFloatOrNull() ?: 100f
    }

    private fun parseColor(value: String?): DeviceRgb {
        if (value == null || !value.startsWith("#")) return DeviceRgb(209, 213, 219) // #d1d5db
        return try {
            val hex = value.removePrefix("#")
            val r = hex.substring(0, 2).toInt(16)
            val g = hex.substring(2, 4).toInt(16)
            val b = hex.substring(4, 6).toInt(16)
            DeviceRgb(r, g, b)
        } catch (_: Exception) {
            DeviceRgb(209, 213, 219)
        }
    }
}

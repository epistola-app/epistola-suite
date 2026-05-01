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
 * - `thickness`: line thickness (e.g. "1pt", "0.5sp", default 1pt)
 * - `width`: line width as percentage (e.g. "100%", default 100%)
 * - `color`: line color (e.g. "#d1d5db", "#fff", "rgb(100,100,100)", default gray)
 * - `style`: line style ("solid", "dashed", "dotted", default solid)
 */
class SeparatorNodeRenderer : NodeRenderer {

    companion object {
        private val DEFAULT_COLOR = DeviceRgb(209, 213, 219) // #d1d5db
    }

    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val props = node.props ?: emptyMap()

        val thickness = (props["thickness"] as? String)?.let {
            StyleApplicator.parseSize(it, context.renderingDefaults.baseFontSizePt, context.spacingUnit)
        } ?: 1f

        val widthPercent = parseWidthPercent(props["width"] as? String)

        val color = (props["color"] as? String)?.let {
            StyleApplicator.parseColor(it)
        } ?: DEFAULT_COLOR

        val border = when (props["style"] as? String) {
            "dashed" -> DashedBorder(color, thickness)
            "dotted" -> DottedBorder(color, thickness)
            else -> SolidBorder(color, thickness)
        }

        // Inner div is the actual line
        val line = Div()
        line.setWidth(UnitValue.createPercentValue(widthPercent))
        line.setHeight(0.5f)
        line.setBorderTop(border)
        line.setHorizontalAlignment(HorizontalAlignment.CENTER)

        // Outer div carries margins from styles
        val wrapper = Div()
        StyleApplicator.applyStylesWithPreset(
            wrapper,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.inheritedStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("separator"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )
        wrapper.add(line)
        wrapper.setHorizontalAlignment(HorizontalAlignment.CENTER)

        return listOf(wrapper)
    }

    private fun parseWidthPercent(value: String?): Float {
        if (value == null || !value.endsWith("%")) return 100f
        return value.removeSuffix("%").toFloatOrNull() ?: 100f
    }
}

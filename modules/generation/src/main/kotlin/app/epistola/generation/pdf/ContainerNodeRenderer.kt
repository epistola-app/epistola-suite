package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement

/**
 * Renders a "container" node to iText elements.
 *
 * A container wraps its slot children in a styled Div.
 * Also used for the "root" node type.
 */
class ContainerNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val div = Div()

        // Apply node styles with theme preset resolution
        StyleApplicator.applyStylesWithPreset(
            div,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.fontCache,
        )

        // Render all slots
        val childElements = registry.renderSlots(node, document, context)
        for (element in childElements) {
            when (element) {
                is com.itextpdf.layout.element.IBlockElement -> div.add(element)
                is com.itextpdf.layout.element.AreaBreak -> div.add(element)
                is com.itextpdf.layout.element.Image -> div.add(element)
            }
        }

        return listOf(div)
    }
}

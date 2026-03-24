package app.epistola.generation.html

import app.epistola.generation.filterNonNullValues
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders "container" and "root" nodes to HTML.
 */
class HtmlContainerNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val style = HtmlStyleApplicator.buildStyleAttribute(
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.renderingDefaults.componentDefaults(node.type),
        )
        val children = registry.renderSlots(node, document, context)
        return if (style.isNotEmpty()) {
            """<div style="$style">$children</div>"""
        } else {
            "<div>$children</div>"
        }
    }
}

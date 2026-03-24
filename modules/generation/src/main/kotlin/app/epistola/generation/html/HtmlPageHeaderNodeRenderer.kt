package app.epistola.generation.html

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "pageheader" node as an HTML header element.
 * Unlike PDF where headers repeat per page, HTML renders it once at the top.
 */
class HtmlPageHeaderNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val children = registry.renderSlots(node, document, context)
        return """<header style="margin-bottom: ${context.renderingDefaults.pageHeaderPadding}pt">$children</header>"""
    }
}

package app.epistola.generation.html

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "pagefooter" node as an HTML footer element.
 * Unlike PDF where footers repeat per page, HTML renders it once at the bottom.
 */
class HtmlPageFooterNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val children = registry.renderSlots(node, document, context)
        return """<footer style="margin-top: ${context.renderingDefaults.pageFooterPadding}pt">$children</footer>"""
    }
}

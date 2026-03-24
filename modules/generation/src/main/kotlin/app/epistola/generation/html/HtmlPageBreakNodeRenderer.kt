package app.epistola.generation.html

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "pagebreak" node as a CSS page-break element.
 */
class HtmlPageBreakNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String = """<div style="page-break-before: always"></div>"""
}

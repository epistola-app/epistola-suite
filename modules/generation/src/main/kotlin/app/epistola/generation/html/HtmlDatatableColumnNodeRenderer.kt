package app.epistola.generation.html

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * No-op renderer for "datatable-column" nodes.
 * Column nodes are rendered by their parent [HtmlDatatableNodeRenderer].
 */
class HtmlDatatableColumnNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String = ""
}

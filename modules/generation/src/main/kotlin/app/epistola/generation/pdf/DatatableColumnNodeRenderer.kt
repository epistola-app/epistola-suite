package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * No-op renderer for "datatable-column" nodes.
 *
 * Column nodes are not rendered independently — they are rendered by
 * their parent [DatatableNodeRenderer] which iterates over them to build
 * the table structure. This renderer exists only to prevent "unknown type"
 * warnings if a column node is encountered outside its parent context.
 */
class DatatableColumnNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> = emptyList()
}

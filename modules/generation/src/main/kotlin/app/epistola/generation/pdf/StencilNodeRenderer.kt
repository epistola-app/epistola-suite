package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renders a "stencil" node to iText elements.
 *
 * Identical to [ContainerNodeRenderer] except for one extra responsibility:
 * a recursion safety net. Every time we enter a stencil node we push its
 * `stencilId` onto [RenderContext.ancestorStencilIds]; if the same id is
 * already in the set we abort with a clear diagnostic. The editor and the
 * server-side `PlaceholderValidator` reject recursive documents long before
 * rendering, so this should never fire — it exists so a corrupted document
 * (or a bug in the validators) cannot infinite-loop the renderer.
 */
class StencilNodeRenderer(
    private val delegate: ContainerNodeRenderer = ContainerNodeRenderer(),
) : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val stencilId = node.props?.get(StencilNodeKeys.PROP_STENCIL_ID) as? String
        val nextContext = if (stencilId != null) {
            check(stencilId !in context.ancestorStencilIds) {
                "Stencil recursion detected: '$stencilId' would contain itself transitively"
            }
            context.copy(ancestorStencilIds = context.ancestorStencilIds + stencilId)
        } else {
            context
        }
        return delegate.render(node, document, nextContext, registry)
    }
}

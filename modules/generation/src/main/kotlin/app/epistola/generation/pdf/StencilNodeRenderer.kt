package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renders a "stencil" node to iText elements.
 *
 * Wraps [ContainerNodeRenderer] with two extra responsibilities:
 *
 *   1. **Recursion safety net.** Every time we enter a stencil node we push
 *      its `stencilId` onto [RenderContext.ancestorStencilIds]; if the same
 *      id is already in the set we abort with a clear diagnostic. The editor
 *      and the server-side `PlaceholderValidator` reject recursive documents
 *      long before rendering, so this should never fire in practice.
 *
 *   2. **Parameter scope.** Resolves the node's parameter schema via
 *      [RenderContext.parameterSchemaProvider], evaluates each binding
 *      against the *outer* context, and pushes the result onto
 *      [RenderContext.parameterScopes] under the configured alias (default
 *      `params`). Inside the stencil's content, expressions like
 *      `params.recipientName` resolve to the bound values.
 *
 * The two responsibilities are independent — the renderer applies the
 * recursion guard first, then asks [ParameterScope] to push parameters.
 * The parameter logic is component-agnostic so a future static-parametrised
 * component can reuse [ParameterScope.push] verbatim.
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
        val afterRecursionGuard = if (stencilId != null) {
            check(stencilId !in context.ancestorStencilIds) {
                "Stencil recursion detected: '$stencilId' would contain itself transitively"
            }
            context.copy(ancestorStencilIds = context.ancestorStencilIds + stencilId)
        } else {
            context
        }

        val schema = afterRecursionGuard.parameterSchemaProvider(node, document)
        val nextContext = ParameterScope.push(node, schema, afterRecursionGuard)

        return delegate.render(node, document, nextContext, registry)
    }
}

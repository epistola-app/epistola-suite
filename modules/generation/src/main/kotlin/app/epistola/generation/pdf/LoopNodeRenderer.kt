package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renders a "loop" node to iText elements.
 *
 * Iterates over the specified array/collection and renders slot children for each item.
 *
 * Props:
 * - `expression`: Expression object stored as Map (with "raw" and optional "language" keys)
 * - `itemAlias`: String (defaults to "item")
 * - `indexAlias`: String (optional)
 */
class LoopNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        // Extract loop expression from props
        val expression = extractExpression(node.props?.get("expression"), context.defaultExpressionLanguage)
            ?: return emptyList()

        // Get the iterable from the expression
        val iterable = context.expressionEvaluator.evaluateIterable(
            expression,
            context.data,
            context.loopContext,
        )

        if (iterable.isEmpty()) return emptyList()

        val itemAlias = node.props?.get("itemAlias") as? String ?: "item"
        val indexAlias = node.props?.get("indexAlias") as? String

        val results = mutableListOf<IElement>()

        // Render slot children for each item
        for ((index, item) in iterable.withIndex()) {
            // Create new loop context with current item
            val itemContext = context.loopContext.toMutableMap()
            itemContext[itemAlias] = item
            itemContext["${itemAlias}_index"] = index
            itemContext["${itemAlias}_first"] = (index == 0)
            itemContext["${itemAlias}_last"] = (index == iterable.size - 1)

            // Add explicit index alias if provided
            if (indexAlias != null) {
                itemContext[indexAlias] = index
            }

            val childContext = context.copy(loopContext = itemContext)

            // Render all slots with new context
            val childElements = registry.renderSlots(node, document, childContext)
            results.addAll(childElements)
        }

        return results
    }
}

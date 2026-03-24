package app.epistola.generation.html

import app.epistola.generation.extractExpression
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "loop" node by iterating over data and rendering children for each item.
 */
class HtmlLoopNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val expression = extractExpression(node.props?.get("expression"), context.defaultExpressionLanguage)
            ?: return ""

        val iterable = context.expressionEvaluator.evaluateIterable(
            expression,
            context.effectiveData,
            context.loopContext,
        )

        if (iterable.isEmpty()) return ""

        val itemAlias = node.props?.get("itemAlias") as? String ?: "item"
        val indexAlias = node.props?.get("indexAlias") as? String

        return buildString {
            for ((index, item) in iterable.withIndex()) {
                val itemContext = context.loopContext.toMutableMap()
                itemContext[itemAlias] = item
                itemContext["${itemAlias}_index"] = index
                itemContext["${itemAlias}_first"] = (index == 0)
                itemContext["${itemAlias}_last"] = (index == iterable.size - 1)
                if (indexAlias != null) {
                    itemContext[indexAlias] = index
                }

                val childContext = context.copy(loopContext = itemContext)
                append(registry.renderSlots(node, document, childContext))
            }
        }
    }
}

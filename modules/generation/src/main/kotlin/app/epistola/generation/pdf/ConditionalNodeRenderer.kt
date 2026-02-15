package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renders a "conditional" node to iText elements.
 *
 * Only renders slot children if the condition evaluates to true (or false if inverse is set).
 *
 * Props:
 * - `condition`: Expression object stored as Map (with "raw" and optional "language" keys)
 * - `inverse`: Boolean (optional, defaults to false)
 */
class ConditionalNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        // Extract condition expression from props
        val expression = extractExpression(node.props?.get("condition"), context.defaultExpressionLanguage)
            ?: return emptyList()

        val inverse = node.props?.get("inverse") as? Boolean ?: false

        // Evaluate the condition
        val conditionResult = context.expressionEvaluator.evaluateCondition(
            expression,
            context.data,
            context.loopContext,
        )

        // Apply inverse if needed
        val shouldRender = if (inverse) !conditionResult else conditionResult

        return if (shouldRender) {
            registry.renderSlots(node, document, context)
        } else {
            emptyList()
        }
    }
}

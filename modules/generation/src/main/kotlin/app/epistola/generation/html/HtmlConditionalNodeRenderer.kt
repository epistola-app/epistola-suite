package app.epistola.generation.html

import app.epistola.generation.extractExpression
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "conditional" node — only renders children if the condition evaluates to true.
 */
class HtmlConditionalNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val expression = extractExpression(node.props?.get("condition"), context.defaultExpressionLanguage)
            ?: return ""

        val inverse = node.props?.get("inverse") as? Boolean ?: false

        val conditionResult = context.expressionEvaluator.evaluateCondition(
            expression,
            context.effectiveData,
            context.loopContext,
        )

        val shouldRender = if (inverse) !conditionResult else conditionResult

        return if (shouldRender) {
            registry.renderSlots(node, document, context)
        } else {
            ""
        }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.IElement

/**
 * Renders a "conditional" node to iText elements.
 *
 * Renders its body (every slot but `else`) when the condition holds (or does not, if inverse is
 * set), and otherwise its `else` slot, when it has one (contract `2.0.0-alpha.3`).
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
            context.effectiveData,
            context.loopContext,
        )

        // Apply inverse if needed
        val shouldRender = if (inverse) !conditionResult else conditionResult

        val (otherwise, body) = node.slots.partition { document.slots[it]?.name == ELSE_SLOT }
        return (if (shouldRender) body else otherwise).flatMap { registry.renderSlot(it, document, context) }
    }

    companion object {
        /** The slot a conditional renders when its condition does not hold. */
        const val ELSE_SLOT = "else"
    }
}

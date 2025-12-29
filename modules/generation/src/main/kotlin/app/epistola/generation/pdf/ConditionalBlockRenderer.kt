package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.ConditionalBlock
import com.itextpdf.layout.element.IBlockElement

/**
 * Renders ConditionalBlock to iText elements.
 * Only renders children if the condition evaluates to true (or false if inverse is set).
 */
class ConditionalBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IBlockElement> {
        if (block !is ConditionalBlock) return emptyList()

        // Evaluate the condition using the Expression object (respects language setting)
        val conditionResult = context.expressionEvaluator.evaluateCondition(
            block.condition,
            context.data,
            context.loopContext,
        )

        // Apply inverse if needed
        val shouldRender = if (block.inverse == true) !conditionResult else conditionResult

        return if (shouldRender) {
            // Render children
            blockRenderers.renderBlocks(block.children, context)
        } else {
            emptyList()
        }
    }
}

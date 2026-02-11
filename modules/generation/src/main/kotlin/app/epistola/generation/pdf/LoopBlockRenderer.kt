package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.LoopBlock
import com.itextpdf.layout.element.IElement

/**
 * Renders LoopBlock to iText elements.
 * Iterates over the specified array/collection and renders children for each item.
 */
class LoopBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement> {
        if (block !is LoopBlock) return emptyList()

        // Get the iterable from the expression (respects language setting)
        val iterable = context.expressionEvaluator.evaluateIterable(
            block.expression,
            context.data,
            context.loopContext,
        )

        if (iterable.isEmpty()) return emptyList()

        val results = mutableListOf<IElement>()
        val itemAlias = block.itemAlias
        val indexAlias = block.indexAlias
        val inheritedStyles = StyleApplicator.resolveInheritedStyles(
            context.inheritedStyles,
            block.stylePreset,
            context.blockStylePresets,
            block.styles,
        )

        // Render children for each item
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

            val childContext = context.copy(
                loopContext = itemContext,
                inheritedStyles = inheritedStyles,
            )

            // Render children with new context
            val childElements = blockRenderers.renderBlocks(block.children, childContext)
            results.addAll(childElements)
        }

        return results
    }
}

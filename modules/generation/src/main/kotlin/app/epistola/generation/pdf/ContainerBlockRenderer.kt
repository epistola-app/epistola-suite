package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.ContainerBlock
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement

/**
 * Renders ContainerBlock to iText elements.
 */
class ContainerBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IBlockElement> {
        if (block !is ContainerBlock) return emptyList()

        val div = Div()

        // Apply block styles
        StyleApplicator.applyStyles(div, block.styles, context.documentStyles, context.fontCache)

        // Render children
        val childElements = blockRenderers.renderBlocks(block.children, context)
        for (element in childElements) {
            div.add(element)
        }

        return listOf(div)
    }
}

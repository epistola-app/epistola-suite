package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.ContainerBlock
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement

/**
 * Renders ContainerBlock to iText elements.
 */
class ContainerBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement> {
        if (block !is ContainerBlock) return emptyList()

        val div = Div()

        // Apply block styles
        StyleApplicator.applyStyles(div, block.styles, context.documentStyles, context.fontCache)

        // Render children
        val childElements = blockRenderers.renderBlocks(block.children, context)
        for (element in childElements) {
            when (element) {
                is com.itextpdf.layout.element.IBlockElement -> div.add(element)
                is com.itextpdf.layout.element.AreaBreak -> div.add(element)
                is com.itextpdf.layout.element.Image -> div.add(element)
            }
        }

        return listOf(div)
    }
}

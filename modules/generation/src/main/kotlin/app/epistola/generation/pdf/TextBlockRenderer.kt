package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.TextBlock
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement

/**
 * Renders TextBlock to iText elements.
 */
class TextBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IBlockElement> {
        if (block !is TextBlock) return emptyList()

        val div = Div()

        // Apply block styles
        StyleApplicator.applyStyles(div, block.styles, context.documentStyles)

        // Convert TipTap content to iText elements
        @Suppress("UNCHECKED_CAST")
        val content = block.content as? Map<String, Any>
        val elements = context.tipTapConverter.convert(content, context.data, context.loopContext)

        for (element in elements) {
            div.add(element)
        }

        return listOf(div)
    }
}

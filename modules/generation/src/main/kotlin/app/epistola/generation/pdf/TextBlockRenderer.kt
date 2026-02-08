package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.TextBlock
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IElement

/**
 * Renders TextBlock to iText elements.
 */
class TextBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement> {
        if (block !is TextBlock) return emptyList()

        val div = Div()

        // Apply block styles with theme preset resolution
        StyleApplicator.applyStylesWithPreset(
            div,
            block.styles,
            block.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.fontCache,
        )

        // Convert TipTap content to iText elements
        val content = block.content
        val elements = context.tipTapConverter.convert(content, context.data, context.loopContext, context.fontCache)

        for (element in elements) {
            div.add(element)
        }

        return listOf(div)
    }
}

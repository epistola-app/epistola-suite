package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.PageFooterBlock
import com.itextpdf.layout.element.IElement

/**
 * Renderer for PageFooterBlock.
 * Returns empty list because footers are rendered via PageFooterEventHandler.
 */
class PageFooterBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement> {
        if (block !is PageFooterBlock) return emptyList()

        // No-op: footers are handled by PageFooterEventHandler
        // which is registered in DirectPdfRenderer
        return emptyList()
    }
}

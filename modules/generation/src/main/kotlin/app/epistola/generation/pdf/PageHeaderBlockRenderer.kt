package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.PageHeaderBlock
import com.itextpdf.layout.element.IElement

/**
 * Renderer for PageHeaderBlock.
 * Returns empty list because headers are rendered via PageHeaderEventHandler.
 */
class PageHeaderBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement> {
        if (block !is PageHeaderBlock) return emptyList()

        // No-op: headers are handled by PageHeaderEventHandler
        // which is registered in DirectPdfRenderer
        return emptyList()
    }
}

package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.PageBreakBlock
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.IElement

/**
 * Renders PageBreakBlock to force content onto a new page.
 */
class PageBreakBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement> {
        if (block !is PageBreakBlock) return emptyList()
        return listOf(AreaBreak())
    }
}

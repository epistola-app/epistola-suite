package app.epistola.generation.pdf

import app.epistola.template.model.Block
import com.itextpdf.layout.element.IElement

/**
 * Interface for rendering template blocks to iText PDF elements.
 */
interface BlockRenderer {
    /**
     * Renders a block to a list of iText elements.
     *
     * @param block The block to render
     * @param context The render context containing data, evaluator, etc.
     * @param blockRenderers Map of all block renderers for recursive rendering
     * @return List of iText elements (can be IBlockElement or AreaBreak)
     */
    fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement>
}

/**
 * Registry of block renderers by block type.
 */
class BlockRendererRegistry(
    initialRenderers: Map<String, BlockRenderer> = emptyMap(),
) {
    private val renderers = initialRenderers.toMutableMap()

    fun register(blockType: String, renderer: BlockRenderer) {
        renderers[blockType] = renderer
    }

    fun render(block: Block, context: RenderContext): List<IElement> {
        val renderer = renderers[block.type]
        return renderer?.render(block, context, this) ?: emptyList()
    }

    fun renderBlocks(blocks: List<Block>, context: RenderContext): List<IElement> = blocks.flatMap { render(it, context) }
}

package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.ColumnsBlock
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.UnitValue

/**
 * Renders ColumnsBlock to iText elements using a table layout.
 */
class ColumnsBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IElement> {
        if (block !is ColumnsBlock) return emptyList()
        if (block.columns.isEmpty()) return emptyList()

        // Calculate total size units
        val totalSize = block.columns.sumOf { it.size }

        // Create column widths as percentages
        val columnWidths = block.columns.map { column ->
            UnitValue.createPercentValue((column.size.toFloat() / totalSize) * 100f)
        }.toTypedArray()

        val table = Table(columnWidths)
        table.useAllAvailableWidth()

        // Apply block styles
        StyleApplicator.applyStyles(table, block.styles, context.documentStyles, context.fontCache)

        // Gap between columns (simulated via cell padding)
        val gap = block.gap?.toFloat() ?: 8f
        val cellPadding = gap / 2f

        // Render each column
        for ((index, column) in block.columns.withIndex()) {
            val cell = Cell()
            cell.setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)

            // Add horizontal padding for gap effect
            if (index > 0) {
                cell.setPaddingLeft(cellPadding)
            }
            if (index < block.columns.size - 1) {
                cell.setPaddingRight(cellPadding)
            }

            // Render column children
            val childElements = blockRenderers.renderBlocks(column.children, context)
            for (element in childElements) {
                when (element) {
                    is com.itextpdf.layout.element.IBlockElement -> cell.add(element)
                    is com.itextpdf.layout.element.Image -> cell.add(element)
                }
            }

            table.addCell(cell)
        }

        return listOf(table)
    }
}

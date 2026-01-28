package app.epistola.generation.pdf

import app.epistola.template.model.Block
import app.epistola.template.model.BorderStyle
import app.epistola.template.model.TableBlock
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.UnitValue

/**
 * Renders TableBlock to iText elements.
 */
class TableBlockRenderer : BlockRenderer {
    override fun render(
        block: Block,
        context: RenderContext,
        blockRenderers: BlockRendererRegistry,
    ): List<IBlockElement> {
        if (block !is TableBlock) return emptyList()
        if (block.rows.isEmpty()) return emptyList()

        // Determine number of columns from first row
        val numColumns = block.rows.firstOrNull()?.cells?.sumOf { it.colspan ?: 1 } ?: 1

        // Create column widths
        val blockColumnWidths = block.columnWidths
        val columnWidths = if (blockColumnWidths != null && blockColumnWidths.isNotEmpty()) {
            val total = blockColumnWidths.sum()
            blockColumnWidths.map { UnitValue.createPercentValue((it.toFloat() / total) * 100f) }.toTypedArray()
        } else {
            // Equal width columns
            Array(numColumns) { UnitValue.createPercentValue(100f / numColumns) }
        }

        val table = Table(columnWidths)
        table.useAllAvailableWidth()

        // Apply block styles
        StyleApplicator.applyStyles(table, block.styles, context.documentStyles, context.fontCache)

        // Border style
        val borderStyle = block.borderStyle ?: BorderStyle.All
        val borderColor = ColorConstants.GRAY
        val borderWidth = 0.5f

        // Render rows
        for (row in block.rows) {
            for (tableCell in row.cells) {
                val colspan = tableCell.colspan ?: 1
                val rowspan = tableCell.rowspan ?: 1

                val cell = Cell(rowspan, colspan)
                cell.setPadding(8f)

                // Apply cell styles
                if (tableCell.styles != null) {
                    StyleApplicator.applyStyles(cell, tableCell.styles, context.documentStyles, context.fontCache)
                }

                // Apply border based on border style
                applyCellBorder(cell, borderStyle, borderColor, borderWidth)

                // Bold for header rows
                if (row.isHeader == true) {
                    cell.setFont(context.fontCache.bold)
                }

                // Render cell children
                val childElements = blockRenderers.renderBlocks(tableCell.children, context)
                for (element in childElements) {
                    cell.add(element)
                }

                table.addCell(cell)
            }
        }

        return listOf(table)
    }

    private fun applyCellBorder(
        cell: Cell,
        borderStyle: BorderStyle,
        borderColor: com.itextpdf.kernel.colors.Color,
        borderWidth: Float,
    ) {
        val solidBorder = SolidBorder(borderColor, borderWidth)

        when (borderStyle) {
            BorderStyle.All -> {
                cell.setBorder(solidBorder)
            }
            BorderStyle.Horizontal -> {
                cell.setBorderTop(solidBorder)
                cell.setBorderBottom(solidBorder)
                cell.setBorderLeft(Border.NO_BORDER)
                cell.setBorderRight(Border.NO_BORDER)
            }
            BorderStyle.Vertical -> {
                cell.setBorderTop(Border.NO_BORDER)
                cell.setBorderBottom(Border.NO_BORDER)
                cell.setBorderLeft(solidBorder)
                cell.setBorderRight(solidBorder)
            }
            BorderStyle.None -> {
                cell.setBorder(Border.NO_BORDER)
            }
        }
    }
}

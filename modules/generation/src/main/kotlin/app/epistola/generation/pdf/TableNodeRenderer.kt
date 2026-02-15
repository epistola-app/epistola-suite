package app.epistola.generation.pdf

import app.epistola.template.model.BorderStyle
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.layout.borders.Border
import com.itextpdf.layout.borders.SolidBorder
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.UnitValue

/**
 * Renders a "table" node to iText elements.
 *
 * Table uses cell slots named "cell-{row}-{col}" (e.g., "cell-0-0", "cell-0-1", "cell-1-0").
 *
 * Props:
 * - `rows`: Int (number of rows)
 * - `columns`: Int (number of columns)
 * - `columnWidths`: List of Int (optional, relative column widths)
 * - `borderStyle`: String (optional, one of "all", "horizontal", "vertical", "none")
 * - `headerRows`: Int (optional, number of header rows, default 0)
 */
class TableNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val rowCount = (node.props?.get("rows") as? Number)?.toInt() ?: return emptyList()
        val colCount = (node.props?.get("columns") as? Number)?.toInt() ?: return emptyList()

        if (rowCount <= 0 || colCount <= 0) return emptyList()

        // Create column widths
        @Suppress("UNCHECKED_CAST")
        val propColumnWidths = (node.props?.get("columnWidths") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }

        val columnWidths = if (propColumnWidths != null && propColumnWidths.size == colCount) {
            val total = propColumnWidths.sum()
            propColumnWidths.map { UnitValue.createPercentValue((it.toFloat() / total) * 100f) }.toTypedArray()
        } else {
            // Equal width columns
            Array(colCount) { UnitValue.createPercentValue(100f / colCount) }
        }

        val table = Table(columnWidths)
        table.useAllAvailableWidth()

        // Apply node styles with theme preset resolution
        StyleApplicator.applyStylesWithPreset(
            table,
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.fontCache,
        )

        // Border style
        val borderStyleStr = node.props?.get("borderStyle") as? String
        val borderStyle = borderStyleStr?.let {
            try {
                BorderStyle.valueOf(it)
            } catch (_: IllegalArgumentException) {
                BorderStyle.all
            }
        } ?: BorderStyle.all

        val borderColor = ColorConstants.GRAY
        val borderWidth = 0.5f
        val headerRows = (node.props?.get("headerRows") as? Number)?.toInt() ?: 0

        // Build a lookup from slot name to slot ID for fast cell slot resolution
        val slotsByName = node.slots.mapNotNull { slotId ->
            document.slots[slotId]?.let { slot -> slot.name to slot.id }
        }.toMap()

        // Render cells in row-major order
        for (row in 0 until rowCount) {
            val isHeaderRow = row < headerRows

            for (col in 0 until colCount) {
                val slotName = "cell-$row-$col"
                val slotId = slotsByName[slotName]

                val cell = Cell()
                cell.setPadding(8f)

                // Apply border based on border style
                applyCellBorder(cell, borderStyle, borderColor, borderWidth)

                // Bold for header rows
                if (isHeaderRow) {
                    cell.setFont(context.fontCache.bold)
                }

                // Render cell slot children
                if (slotId != null) {
                    val childElements = registry.renderSlot(slotId, document, context)
                    for (element in childElements) {
                        when (element) {
                            is com.itextpdf.layout.element.IBlockElement -> cell.add(element)
                            is com.itextpdf.layout.element.Image -> cell.add(element)
                        }
                    }
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
            BorderStyle.all -> {
                cell.setBorder(solidBorder)
            }
            BorderStyle.horizontal -> {
                cell.setBorderTop(solidBorder)
                cell.setBorderBottom(solidBorder)
                cell.setBorderLeft(Border.NO_BORDER)
                cell.setBorderRight(Border.NO_BORDER)
            }
            BorderStyle.vertical -> {
                cell.setBorderTop(Border.NO_BORDER)
                cell.setBorderBottom(Border.NO_BORDER)
                cell.setBorderLeft(solidBorder)
                cell.setBorderRight(solidBorder)
            }
            BorderStyle.none -> {
                cell.setBorder(Border.NO_BORDER)
            }
        }
    }
}

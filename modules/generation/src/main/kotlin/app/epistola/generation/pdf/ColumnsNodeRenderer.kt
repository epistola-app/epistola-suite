package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.UnitValue

/**
 * Renders a "columns" node to iText elements using a table layout.
 *
 * Columns use named slots: "column-0", "column-1", etc.
 * Slots are rendered in order based on their index suffix.
 *
 * Props:
 * - `gap`: Number (optional, spacing between columns in points, default 8)
 * - `columnSizes`: List of size values (optional, relative column widths, default equal)
 */
class ColumnsNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        if (node.slots.isEmpty()) return emptyList()

        // Resolve slots in order, sorted by their column index from the slot name
        val columnSlots = node.slots.mapNotNull { slotId ->
            document.slots[slotId]
        }.sortedBy { slot ->
            // Extract index from "column-N" name, fallback to 0
            slot.name.removePrefix("column-").toIntOrNull() ?: 0
        }

        if (columnSlots.isEmpty()) return emptyList()

        // Get column sizes from props (default to equal sizing)
        @Suppress("UNCHECKED_CAST")
        val columnSizesList = node.props?.get("columnSizes") as? List<*>
        val columnSizes = columnSizesList?.mapNotNull { (it as? Number)?.toInt() }

        // Calculate column widths as percentages
        val columnWidths = if (columnSizes != null && columnSizes.size == columnSlots.size) {
            val totalSize = columnSizes.sum()
            columnSizes.map { size ->
                UnitValue.createPercentValue((size.toFloat() / totalSize) * 100f)
            }.toTypedArray()
        } else {
            // Equal width columns
            Array(columnSlots.size) { UnitValue.createPercentValue(100f / columnSlots.size) }
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
            StyleApplicator.COMPONENT_DEFAULTS["columns"],
        )

        // Gap between columns (simulated via cell padding)
        val gap = (node.props?.get("gap") as? Number)?.toFloat() ?: 8f
        val cellPadding = gap / 2f

        // Render each column slot
        for ((index, slot) in columnSlots.withIndex()) {
            val cell = Cell()
            cell.setBorder(com.itextpdf.layout.borders.Border.NO_BORDER)

            // Add horizontal padding for gap effect
            if (index > 0) {
                cell.setPaddingLeft(cellPadding)
            }
            if (index < columnSlots.size - 1) {
                cell.setPaddingRight(cellPadding)
            }

            // Render slot children
            val childElements = registry.renderSlot(slot.id, document, context)
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

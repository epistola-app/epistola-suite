package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.UnitValue

/**
 * Renders a "datatable" node to iText elements.
 *
 * Combines the iteration logic of [LoopNodeRenderer] (expression evaluation, loop context)
 * with the table rendering of [TableNodeRenderer] (iText Table, cells, borders).
 *
 * The datatable's "columns" slot contains child nodes of type "datatable-column".
 * Each column has:
 * - `props.header`: header text
 * - `props.width`: relative column width
 * - A "body" slot whose content is the per-row template
 *
 * Parent props:
 * - `expression`: Expression object (with "raw" and optional "language" keys)
 * - `itemAlias`: String (defaults to "item")
 * - `indexAlias`: String (optional)
 * - `borderStyle`: String (optional, one of "all", "horizontal", "vertical", "none")
 * - `headerEnabled`: Boolean (default true)
 */
class DatatableNodeRenderer : NodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        // Extract loop expression from props
        val expression = extractExpression(node.props?.get("expression"), context.defaultExpressionLanguage)
            ?: return emptyList()

        // Get the iterable from the expression
        val iterable = context.expressionEvaluator.evaluateIterable(
            expression,
            context.data,
            context.loopContext,
        )

        val headerEnabled = node.props?.get("headerEnabled") as? Boolean ?: true

        // If no data and no header, nothing to render
        if (iterable.isEmpty() && !headerEnabled) return emptyList()

        // Find column nodes from the "columns" slot
        val columnsSlotId = node.slots.firstOrNull() ?: return emptyList()
        val columnsSlot = document.slots[columnsSlotId] ?: return emptyList()
        val columnNodes = columnsSlot.children.mapNotNull { document.nodes[it] }

        if (columnNodes.isEmpty()) return emptyList()

        // Calculate column widths
        val columnWidths = columnNodes.map { cn ->
            val width = (cn.props?.get("width") as? Number)?.toFloat() ?: 33f
            UnitValue.createPercentValue(width)
        }.toTypedArray()

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
        val borderStyle = parseBorderStyle(node.props?.get("borderStyle") as? String)
        val borderColor = ColorConstants.GRAY
        val borderWidth = 0.5f

        // Render header row
        if (headerEnabled) {
            for (columnNode in columnNodes) {
                val headerText = columnNode.props?.get("header") as? String ?: ""
                val cell = Cell()
                cell.setPadding(8f)
                cell.setFont(context.fontCache.bold)
                cell.add(Paragraph(headerText))
                applyCellBorder(cell, borderStyle, borderColor, borderWidth)

                // Apply column-level styles
                applyColumnStyles(cell, columnNode, context)

                table.addCell(cell)
            }
        }

        // Render data rows
        val itemAlias = node.props?.get("itemAlias") as? String ?: "item"
        val indexAlias = node.props?.get("indexAlias") as? String

        for ((index, item) in iterable.withIndex()) {
            // Create loop context with current item
            val itemContext = context.loopContext.toMutableMap()
            itemContext[itemAlias] = item
            itemContext["${itemAlias}_index"] = index
            itemContext["${itemAlias}_first"] = (index == 0)
            itemContext["${itemAlias}_last"] = (index == iterable.size - 1)

            if (indexAlias != null) {
                itemContext[indexAlias] = index
            }

            val childContext = context.copy(loopContext = itemContext)

            // Render each column's body slot with the loop context
            for (columnNode in columnNodes) {
                val bodySlotId = columnNode.slots.firstOrNull()
                val cell = Cell()
                cell.setPadding(8f)
                applyCellBorder(cell, borderStyle, borderColor, borderWidth)

                // Apply column-level styles
                applyColumnStyles(cell, columnNode, context)

                if (bodySlotId != null) {
                    val childElements = registry.renderSlot(bodySlotId, document, childContext)
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

    /**
     * Apply column-node-level styles (padding, background, borders) to a cell.
     */
    private fun applyColumnStyles(cell: Cell, columnNode: Node, context: RenderContext) {
        val styles = columnNode.styles?.filterNonNullValues() ?: return
        StyleApplicator.applyStylesWithPreset(
            cell,
            styles,
            columnNode.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.fontCache,
        )
    }
}

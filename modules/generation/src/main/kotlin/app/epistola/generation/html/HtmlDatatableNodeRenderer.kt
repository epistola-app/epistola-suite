package app.epistola.generation.html

import app.epistola.generation.extractExpression
import app.epistola.generation.filterNonNullValues
import app.epistola.generation.parseBorderStyle
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "datatable" node to an HTML table with data-driven rows.
 */
class HtmlDatatableNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val expression = extractExpression(node.props?.get("expression"), context.defaultExpressionLanguage)
            ?: return ""

        val iterable = context.expressionEvaluator.evaluateIterable(
            expression,
            context.effectiveData,
            context.loopContext,
        )

        val headerEnabled = node.props?.get("headerEnabled") as? Boolean ?: true
        if (iterable.isEmpty() && !headerEnabled) return ""

        val columnsSlotId = node.slots.firstOrNull() ?: return ""
        val columnsSlot = document.slots[columnsSlotId] ?: return ""
        val columnNodes = columnsSlot.children.mapNotNull { document.nodes[it] }
        if (columnNodes.isEmpty()) return ""

        val borderStyle = parseBorderStyle(node.props?.get("borderStyle") as? String)
        val borderColor = context.renderingDefaults.tableBorderColorHex
        val borderWidth = context.renderingDefaults.tableBorderWidth
        val cellPadding = context.renderingDefaults.tableCellPadding

        val nodeStyle = HtmlStyleApplicator.buildStyleAttribute(
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.renderingDefaults.componentDefaults("datatable"),
        )

        val tableStyle = "width: 100%; border-collapse: collapse"
        val combinedStyle = if (nodeStyle.isNotEmpty()) "$tableStyle; $nodeStyle" else tableStyle

        return buildString {
            append("""<table style="$combinedStyle">""")

            // Column widths
            append("<colgroup>")
            for (cn in columnNodes) {
                val width = (cn.props?.get("width") as? Number)?.toFloat()
                    ?: context.renderingDefaults.datatableDefaultColumnWidthPercent
                append("""<col style="width: $width%">""")
            }
            append("</colgroup>")

            // Header row
            if (headerEnabled) {
                append("<thead><tr>")
                for (columnNode in columnNodes) {
                    val headerText = columnNode.props?.get("header") as? String ?: ""
                    val cellCss = HtmlTableNodeRenderer.buildCellBorderCss(borderStyle, borderColor, borderWidth) +
                        "; padding: ${cellPadding}pt; font-weight: bold"
                    val colStyle = buildColumnStyleCss(columnNode, context)
                    val fullCss = if (colStyle.isNotEmpty()) "$cellCss; $colStyle" else cellCss
                    append("""<th style="$fullCss">${HtmlEscaper.escape(headerText)}</th>""")
                }
                append("</tr></thead>")
            }

            // Data rows
            val itemAlias = node.props?.get("itemAlias") as? String ?: "item"
            val indexAlias = node.props?.get("indexAlias") as? String

            append("<tbody>")
            for ((index, item) in iterable.withIndex()) {
                val itemContext = context.loopContext.toMutableMap()
                itemContext[itemAlias] = item
                itemContext["${itemAlias}_index"] = index
                itemContext["${itemAlias}_first"] = (index == 0)
                itemContext["${itemAlias}_last"] = (index == iterable.size - 1)
                if (indexAlias != null) {
                    itemContext[indexAlias] = index
                }

                val childContext = context.copy(loopContext = itemContext)

                append("<tr>")
                for (columnNode in columnNodes) {
                    val bodySlotId = columnNode.slots.firstOrNull()
                    val cellCss = HtmlTableNodeRenderer.buildCellBorderCss(borderStyle, borderColor, borderWidth) +
                        "; padding: ${cellPadding}pt"
                    val colStyle = buildColumnStyleCss(columnNode, context)
                    val fullCss = if (colStyle.isNotEmpty()) "$cellCss; $colStyle" else cellCss
                    val children = if (bodySlotId != null) registry.renderSlot(bodySlotId, document, childContext) else ""
                    append("""<td style="$fullCss">$children</td>""")
                }
                append("</tr>")
            }
            append("</tbody>")

            append("</table>")
        }
    }

    private fun buildColumnStyleCss(columnNode: Node, context: HtmlRenderContext): String {
        val styles = columnNode.styles?.filterNonNullValues() ?: return ""
        return HtmlStyleApplicator.buildStyleAttribute(
            styles,
            columnNode.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
        )
    }
}

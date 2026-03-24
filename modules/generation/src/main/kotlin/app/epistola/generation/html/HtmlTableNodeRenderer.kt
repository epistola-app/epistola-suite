package app.epistola.generation.html

import app.epistola.generation.filterNonNullValues
import app.epistola.generation.parseBorderStyle
import app.epistola.template.model.BorderStyle
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "table" node to an HTML table.
 */
class HtmlTableNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        val rowCount = (node.props?.get("rows") as? Number)?.toInt() ?: return ""
        val colCount = (node.props?.get("columns") as? Number)?.toInt() ?: return ""
        if (rowCount <= 0 || colCount <= 0) return ""

        @Suppress("UNCHECKED_CAST")
        val propColumnWidths = (node.props?.get("columnWidths") as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }

        val borderStyle = parseBorderStyle(node.props?.get("borderStyle") as? String)
        val borderColor = context.renderingDefaults.tableBorderColorHex
        val borderWidth = context.renderingDefaults.tableBorderWidth
        val cellPadding = context.renderingDefaults.tableCellPadding
        val headerRows = (node.props?.get("headerRows") as? Number)?.toInt() ?: 0

        val merges = parseMerges(node.props)
        val coveredCells = buildCoveredCellsSet(merges)
        val mergeByAnchor = merges.associateBy { it.row to it.col }

        val slotsByName = node.slots.mapNotNull { slotId ->
            document.slots[slotId]?.let { slot -> slot.name to slot.id }
        }.toMap()

        val nodeStyle = HtmlStyleApplicator.buildStyleAttribute(
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.renderingDefaults.componentDefaults("table"),
        )

        val tableStyle = "width: 100%; border-collapse: collapse"
        val combinedStyle = if (nodeStyle.isNotEmpty()) "$tableStyle; $nodeStyle" else tableStyle

        return buildString {
            append("""<table style="$combinedStyle">""")

            // Column widths via colgroup
            if (propColumnWidths != null && propColumnWidths.size == colCount) {
                val total = propColumnWidths.sum()
                append("<colgroup>")
                for (w in propColumnWidths) {
                    val pct = (w.toFloat() / total) * 100f
                    append("""<col style="width: ${"%.1f".format(pct)}%">""")
                }
                append("</colgroup>")
            }

            for (row in 0 until rowCount) {
                val isHeaderRow = row < headerRows
                append("<tr>")
                for (col in 0 until colCount) {
                    if ((row to col) in coveredCells) continue

                    val merge = mergeByAnchor[row to col]
                    val rowSpan = merge?.rowSpan ?: 1
                    val colSpan = merge?.colSpan ?: 1

                    val tag = if (isHeaderRow) "th" else "td"
                    val cellCss = buildCellBorderCss(borderStyle, borderColor, borderWidth) +
                        "; padding: ${cellPadding}pt" +
                        if (isHeaderRow) "; font-weight: bold" else ""

                    val spanAttrs = buildString {
                        if (rowSpan > 1) append(""" rowspan="$rowSpan"""")
                        if (colSpan > 1) append(""" colspan="$colSpan"""")
                    }

                    val slotName = "cell-$row-$col"
                    val slotId = slotsByName[slotName]
                    val children = if (slotId != null) registry.renderSlot(slotId, document, context) else ""

                    append("""<$tag style="$cellCss"$spanAttrs>$children</$tag>""")
                }
                append("</tr>")
            }

            append("</table>")
        }
    }

    private data class CellMerge(val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int)

    private fun parseMerges(props: Map<String, Any?>?): List<CellMerge> {
        val raw = props?.get("merges") as? List<*> ?: return emptyList()
        return raw.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val row = (map["row"] as? Number)?.toInt() ?: return@mapNotNull null
            val col = (map["col"] as? Number)?.toInt() ?: return@mapNotNull null
            val rowSpan = (map["rowSpan"] as? Number)?.toInt() ?: return@mapNotNull null
            val colSpan = (map["colSpan"] as? Number)?.toInt() ?: return@mapNotNull null
            if (rowSpan < 1 || colSpan < 1) return@mapNotNull null
            CellMerge(row, col, rowSpan, colSpan)
        }
    }

    private fun buildCoveredCellsSet(merges: List<CellMerge>): Set<Pair<Int, Int>> {
        val covered = mutableSetOf<Pair<Int, Int>>()
        for (merge in merges) {
            for (r in merge.row until merge.row + merge.rowSpan) {
                for (c in merge.col until merge.col + merge.colSpan) {
                    if (r == merge.row && c == merge.col) continue
                    covered.add(r to c)
                }
            }
        }
        return covered
    }

    companion object {
        fun buildCellBorderCss(borderStyle: BorderStyle, borderColor: String, borderWidth: Float): String {
            val solid = "${borderWidth}pt solid $borderColor"
            val none = "none"
            return when (borderStyle) {
                BorderStyle.all -> "border: $solid"
                BorderStyle.horizontal -> "border-top: $solid; border-bottom: $solid; border-left: $none; border-right: $none"
                BorderStyle.vertical -> "border-top: $none; border-bottom: $none; border-left: $solid; border-right: $solid"
                BorderStyle.none -> "border: $none"
            }
        }
    }
}

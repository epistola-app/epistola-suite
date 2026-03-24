package app.epistola.generation.html

import app.epistola.generation.filterNonNullValues
import app.epistola.template.model.Node
import app.epistola.template.model.TemplateDocument

/**
 * Renders a "columns" node to an HTML flex layout.
 */
class HtmlColumnsNodeRenderer : HtmlNodeRenderer {
    override fun render(
        node: Node,
        document: TemplateDocument,
        context: HtmlRenderContext,
        registry: HtmlNodeRendererRegistry,
    ): String {
        if (node.slots.isEmpty()) return ""

        val columnSlots = node.slots.mapNotNull { slotId ->
            document.slots[slotId]
        }.sortedBy { slot ->
            slot.name.removePrefix("column-").toIntOrNull() ?: 0
        }

        if (columnSlots.isEmpty()) return ""

        @Suppress("UNCHECKED_CAST")
        val columnSizesList = node.props?.get("columnSizes") as? List<*>
        val columnSizes = columnSizesList?.mapNotNull { (it as? Number)?.toInt() }

        val gap = (node.props?.get("gap") as? Number)?.toFloat() ?: context.renderingDefaults.columnGap

        val nodeStyle = HtmlStyleApplicator.buildStyleAttribute(
            node.styles?.filterNonNullValues(),
            node.stylePreset,
            context.blockStylePresets,
            context.documentStyles,
            context.renderingDefaults.componentDefaults("columns"),
        )

        val flexStyle = "display: flex; gap: ${gap}pt"
        val combinedStyle = if (nodeStyle.isNotEmpty()) "$flexStyle; $nodeStyle" else flexStyle

        val columns = buildString {
            val totalSize = if (columnSizes != null && columnSizes.size == columnSlots.size) {
                columnSizes.sum()
            } else {
                0
            }

            for ((index, slot) in columnSlots.withIndex()) {
                val widthPercent = if (totalSize > 0 && columnSizes != null) {
                    (columnSizes[index].toFloat() / totalSize) * 100f
                } else {
                    100f / columnSlots.size
                }
                val children = registry.renderSlot(slot.id, document, context)
                append("""<div style="flex: 0 0 calc(${"%.1f".format(widthPercent)}% - ${gap * (columnSlots.size - 1) / columnSlots.size}pt)">$children</div>""")
            }
        }

        return """<div style="$combinedStyle">$columns</div>"""
    }
}

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.TemplateDocument
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.IElement
import com.itextpdf.layout.element.Image

/**
 * Renders the aside content in the document flow with a margin to avoid
 * the address area. The address content is rendered at absolute coordinates
 * by [AddressBlockEventHandler].
 *
 * The aside div has:
 * - Left/right margin matching the address area so text wraps beside the address
 * - Min-height ensuring body content after starts below the address bottom
 *
 * [DirectPdfRenderer.hoistAddressBlock] moves this to the first child of root.
 */
class AddressBlockNodeRenderer : NodeRenderer {

    companion object {
        private const val MM_TO_PT = 2.834645f
    }

    override fun render(
        node: Node,
        document: TemplateDocument,
        context: RenderContext,
        registry: NodeRendererRegistry,
    ): List<IElement> {
        val props = node.props ?: return emptyList()
        val align = props["align"] as? String ?: "left"
        val topMm = (props["top"] as? Number)?.toFloat() ?: 45f
        val sideDistanceMm = (props["sideDistance"] as? Number)?.toFloat() ?: 20f
        val addressWidthMm = (props["addressWidth"] as? Number)?.toFloat() ?: 85f
        val heightMm = (props["height"] as? Number)?.toFloat() ?: 45f

        val addressBottomPt = (topMm + heightMm) * MM_TO_PT

        val pageSettings = document.pageSettingsOverride ?: context.renderingDefaults.defaultPageSettings
        val pageMargins = pageSettings.margins
        val pageTopMarginPt = pageMargins.top.toFloat() * MM_TO_PT
        val pageLeftMarginPt = pageMargins.left.toFloat() * MM_TO_PT

        val headerNode = document.nodes.values.firstOrNull { it.type == "pageheader" }
        val headerHeightPt = if (headerNode != null) {
            val h = parseNodeHeight(headerNode, context) ?: context.renderingDefaults.pageHeaderHeight
            h + context.renderingDefaults.pageHeaderPadding
        } else {
            0f
        }

        val contentAreaTopPt = pageTopMarginPt + headerHeightPt
        val isRight = align == "right"

        // Render aside slot
        val slotsByName = node.slots.mapNotNull { slotId ->
            document.slots[slotId]?.let { slot -> slot.name to slot.id }
        }.toMap()
        val childContext = context.withInheritedStylesFrom(node)
        val asideElements = slotsByName["aside"]?.let { registry.renderSlot(it, document, childContext) } ?: emptyList()

        val asideDiv = Div()
        // Margin on the address side to leave room for the absolute-positioned address.
        val gapPt = 4f * MM_TO_PT // small gap between address and aside
        if (isRight) {
            // sideDistance is from the right page edge; compute margin from content area right edge
            val pageWidthMm = pageWidthMm(pageSettings.format, pageSettings.orientation)
            val addressLeftEdgePt = (pageWidthMm - sideDistanceMm - addressWidthMm) * MM_TO_PT
            val contentRightEdgePt = (pageWidthMm - pageMargins.right.toFloat()) * MM_TO_PT
            asideDiv.setMarginRight(maxOf(0f, contentRightEdgePt - addressLeftEdgePt + gapPt))
        } else {
            // sideDistance is from the left page edge
            val addressRightEdgePt = (sideDistanceMm + addressWidthMm) * MM_TO_PT
            asideDiv.setMarginLeft(maxOf(0f, addressRightEdgePt - pageLeftMarginPt + gapPt))
        }
        asideDiv.setMarginTop(0f)
        asideDiv.setMarginBottom(0f)
        asideDiv.setPadding(0f)
        // Ensure body content starts below the address bottom
        asideDiv.setMinHeight(maxOf(0f, addressBottomPt - contentAreaTopPt))

        for (element in asideElements) {
            when (element) {
                is IBlockElement -> asideDiv.add(element)
                is Image -> asideDiv.add(element)
            }
        }

        return listOf(asideDiv)
    }

    private fun pageWidthMm(format: PageFormat, orientation: Orientation): Float = when (format) {
        PageFormat.A4 -> if (orientation == Orientation.landscape) 297f else 210f
        PageFormat.Letter -> if (orientation == Orientation.landscape) 279.4f else 215.9f
        PageFormat.Custom -> if (orientation == Orientation.landscape) 297f else 210f
    }
}

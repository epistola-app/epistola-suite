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
 * - Left margin matching the address width (for left window) so text wraps beside the address
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
        val standard = props["standard"] as? String ?: "din-c56-left"
        val topMm = (props["top"] as? Number)?.toFloat() ?: 45f
        val leftMm = (props["left"] as? Number)?.toFloat() ?: 20f
        val addressWidthMm = (props["addressWidth"] as? Number)?.toFloat() ?: 85f
        val heightMm = (props["height"] as? Number)?.toFloat() ?: 45f

        val addressBottomPt = (topMm + heightMm) * MM_TO_PT

        val pageMargins = document.pageSettingsOverride?.margins
            ?: context.renderingDefaults.defaultPageSettings.margins
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
        val isRightWindow = standard == "din-c56-right"

        // Render aside slot
        val slotsByName = node.slots.mapNotNull { slotId ->
            document.slots[slotId]?.let { slot -> slot.name to slot.id }
        }.toMap()
        val asideElements = slotsByName["aside"]?.let { registry.renderSlot(it, document, context) } ?: emptyList()

        val asideDiv = Div()
        // Margin on the address side to leave room for the absolute-positioned address.
        // The address right edge (from page edge) minus the content area left edge (page left margin)
        // gives the margin needed in the content area.
        val gapPt = 4f * MM_TO_PT // small gap between address and aside
        if (isRightWindow) {
            val pageSettings = document.pageSettingsOverride ?: context.renderingDefaults.defaultPageSettings
            val pageWidthMm = when (pageSettings.format) {
                PageFormat.A4 -> if (pageSettings.orientation == Orientation.landscape) 297f else 210f
                PageFormat.Letter -> if (pageSettings.orientation == Orientation.landscape) 279.4f else 215.9f
                PageFormat.Custom -> if (pageSettings.orientation == Orientation.landscape) 297f else 210f
            }
            val contentRightEdgePt = (pageWidthMm - pageMargins.right.toFloat()) * MM_TO_PT
            val addressLeftEdgePt = leftMm * MM_TO_PT
            asideDiv.setMarginRight(maxOf(0f, contentRightEdgePt - addressLeftEdgePt + gapPt))
        } else {
            val addressRightEdgeFromPageEdge = (leftMm + addressWidthMm) * MM_TO_PT
            val addressRightEdgeFromContentLeft = addressRightEdgeFromPageEdge - pageLeftMarginPt
            asideDiv.setMarginLeft(maxOf(0f, addressRightEdgeFromContentLeft + gapPt))
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
}

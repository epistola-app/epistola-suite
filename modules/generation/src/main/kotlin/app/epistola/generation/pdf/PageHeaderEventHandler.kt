package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.canvas.CanvasArtifact
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Image

/**
 * Event handler that renders a page header on every page.
 *
 * Accepts an ordered list of header node IDs (size 1 or 2). Mapping is positional:
 *  - 1 header → applies to every page.
 *  - 2 headers → index 0 applies to page 1; index 1 applies to pages 2 and onward.
 *
 * The body's top margin is computed by the caller from the maximum header height
 * across the list, so the body never overlaps either variant.
 */
class PageHeaderEventHandler(
    private val headerNodeIds: List<String>,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
) : AbstractPdfDocumentEventHandler() {

    init {
        require(headerNodeIds.isNotEmpty() && headerNodeIds.size <= 2) {
            "PageHeaderEventHandler expects 1 or 2 header node IDs, got ${headerNodeIds.size}"
        }
    }

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val pageSize = page.pageSize

        val pageNumber = pdfDoc.getPageNumber(page)
        val selectedId = if (pageNumber == 1 || headerNodeIds.size == 1) {
            headerNodeIds[0]
        } else {
            headerNodeIds[1]
        }
        val headerNode = document.nodes[selectedId] ?: return

        // --- header band (top of page) ---
        // The header rectangle's distance to each page edge follows the cascade:
        // headerNode.margin{Top,Left,Right} → root.margin{Top,Left,Right} →
        // pageSettings.margins.{top,left,right} (template > theme > engine defaults).
        val topMargin = effectivePageMarginPt(headerNode, "marginTop", context)
        val leftMargin = effectivePageMarginPt(headerNode, "marginLeft", context)
        val rightMargin = effectivePageMarginPt(headerNode, "marginRight", context)
        val headerHeight = parseNodeHeight(headerNode, context)
            ?: context.renderingDefaults.pageHeaderHeight

        // Rectangle y is measured from the bottom of the page.
        // For a header we place it at: pageTop - topMargin - headerHeight.
        val headerRect = Rectangle(
            pageSize.left + leftMargin,
            pageSize.top - topMargin - headerHeight,
            pageSize.width - leftMargin - rightMargin,
            headerHeight,
        )

        // Draw after normal page content (overlay)
        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)

        // Mark the running header as an artifact so screen readers skip it (WCAG PDF14)
        pdfCanvas.openTag(CanvasArtifact())

        // Constrain layout to the header rectangle
        val canvas = Canvas(pdfCanvas, headerRect)

        // Render the header node's slots with page-scoped system parameters
        val totalPages = context.totalPages ?: pdfDoc.numberOfPages
        val pageContext = context.withInheritedStylesFrom(headerNode).withPageParams(pageNumber, totalPages)
        val elements = registry.renderSlots(headerNode, document, pageContext)

        // Wrap slot children in a Div so header node styles (borders, background, padding) apply.
        // The margin sides consumed above for rectangle positioning are stripped from the
        // wrapper styles so the same values aren't applied again inside the rectangle.
        val wrapper = Div()
        val wrapperStyles = headerNode.styleMapExcluding(setOf("marginTop", "marginLeft", "marginRight"))
        StyleApplicator.applyStylesWithPreset(
            wrapper,
            wrapperStyles,
            headerNode.stylePreset,
            context.blockStylePresets,
            context.inheritedStyles,
            context.fontCache,
            context.renderingDefaults.componentDefaults("pageheader"),
            context.renderingDefaults.baseFontSizePt,
            context.spacingUnit,
        )
        for (element in elements) {
            when (element) {
                is IBlockElement -> wrapper.add(element)
                is Image -> wrapper.add(element)
                is AreaBreak -> Unit
            }
        }
        canvas.add(wrapper)

        canvas.close()
        pdfCanvas.closeTag()
        pdfCanvas.release()
    }
}

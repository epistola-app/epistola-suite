package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Rectangle
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
 * Registered to handle END_PAGE events and draws header content at the top of each page.
 */
class PageHeaderEventHandler(
    private val headerNodeId: String,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val pageSize = page.pageSize

        // --- header band (top of page) ---
        // The header rectangle's distance to each page edge follows the cascade:
        // headerNode.margin{Top,Left,Right} → root.margin{Top,Left,Right} →
        // pageSettings.margins.{top,left,right} (template > theme > engine defaults).
        val headerNode = document.nodes[headerNodeId]
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

        // Constrain layout to the header rectangle
        val canvas = Canvas(pdfCanvas, headerRect)

        // Render the header node's slots with page-scoped system parameters
        if (headerNode != null) {
            val pageNumber = pdfDoc.getPageNumber(page)
            val hideOnFirstPage = headerNode.props?.get("hideOnFirstPage") == true
            if (hideOnFirstPage && pageNumber == 1) return
            val totalPages = context.totalPages ?: pdfDoc.numberOfPages
            val pageContext = context.withInheritedStylesFrom(headerNode).withPageParams(pageNumber, totalPages)
            val elements = registry.renderSlots(headerNode, document, pageContext)

            // Wrap slot children in a Div so header node styles (borders, background, padding) apply.
            // The margin sides consumed above for rectangle positioning are stripped from the
            // wrapper styles so the same values aren't applied again inside the rectangle.
            val wrapper = Div()
            val consumedMarginKeys = setOf("marginTop", "marginLeft", "marginRight")
            val wrapperStyles = headerNode.styles?.filterNonNullValues()?.filterKeys { it !in consumedMarginKeys }
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
        }

        canvas.close()
        pdfCanvas.release()
    }
}

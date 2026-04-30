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
 * Event handler that renders a page footer on every page.
 * Registered to handle END_PAGE events and draws footer content at the bottom of each page.
 */
class PageFooterEventHandler(
    private val footerNodeId: String,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val pageSize = page.pageSize

        // --- footer band (bottom of page) ---
        // The footer rectangle's distance to each page edge follows the cascade:
        // footerNode.margin{Bottom,Left,Right} → root.margin{Bottom,Left,Right} →
        // pageSettings.margins.{bottom,left,right} (template > theme > engine defaults).
        val footerNode = document.nodes[footerNodeId]
        val bottomMargin = effectivePageMarginPt(footerNode, "marginBottom", context)
        val leftMargin = effectivePageMarginPt(footerNode, "marginLeft", context)
        val rightMargin = effectivePageMarginPt(footerNode, "marginRight", context)
        val footerHeight = parseNodeHeight(footerNode, context)
            ?: context.renderingDefaults.pageFooterHeight

        val footerRect = Rectangle(
            pageSize.left + leftMargin,
            pageSize.bottom + bottomMargin,
            pageSize.width - leftMargin - rightMargin,
            footerHeight,
        )

        // Write after normal page content
        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)

        val canvas = Canvas(pdfCanvas, footerRect)

        // Render the footer node's slots with page-scoped system parameters
        if (footerNode != null) {
            val pageNumber = pdfDoc.getPageNumber(page)
            val hideOnFirstPage = footerNode.props?.get("hideOnFirstPage") == true
            if (hideOnFirstPage && pageNumber == 1) return
            val totalPages = context.totalPages ?: pdfDoc.numberOfPages
            val pageContext = context.withInheritedStylesFrom(footerNode).withPageParams(pageNumber, totalPages)
            val elements = registry.renderSlots(footerNode, document, pageContext)

            // Wrap slot children in a Div so footer node styles (borders, background, padding) apply.
            // The margin sides consumed above for rectangle positioning are stripped from the
            // wrapper styles so the same values aren't applied again inside the rectangle.
            val wrapper = Div()
            val consumedMarginKeys = setOf("marginBottom", "marginLeft", "marginRight")
            val wrapperStyles = footerNode.styles?.filterNonNullValues()?.filterKeys { it !in consumedMarginKeys }
            StyleApplicator.applyStylesWithPreset(
                wrapper,
                wrapperStyles,
                footerNode.stylePreset,
                context.blockStylePresets,
                context.inheritedStyles,
                context.fontCache,
                context.renderingDefaults.componentDefaults("pagefooter"),
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

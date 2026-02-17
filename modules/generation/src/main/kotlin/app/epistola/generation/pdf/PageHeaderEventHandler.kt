package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.element.AreaBreak
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
        val leftPadding = 36f
        val rightPadding = 36f
        val topPadding = 20f
        val headerHeight = 60f

        // Rectangle y is measured from the bottom of the page.
        // So for a header we place it at: top - topPadding - headerHeight
        val headerRect = Rectangle(
            pageSize.left + leftPadding,
            pageSize.top - topPadding - headerHeight,
            pageSize.width - leftPadding - rightPadding,
            headerHeight,
        )

        // Draw after normal page content (overlay)
        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)

        // Constrain layout to the header rectangle
        val canvas = Canvas(pdfCanvas, headerRect)

        // Render the header node's slots
        val headerNode = document.nodes[headerNodeId]
        if (headerNode != null) {
            val elements = registry.renderSlots(headerNode, document, context)
            for (element in elements) {
                when (element) {
                    is IBlockElement -> canvas.add(element)
                    is Image -> canvas.add(element)
                    is AreaBreak -> Unit
                }
            }
        }

        canvas.close()
        pdfCanvas.release()
    }
}

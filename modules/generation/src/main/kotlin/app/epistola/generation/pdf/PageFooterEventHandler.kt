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
        val leftPadding = 36f
        val rightPadding = 36f
        val bottomPadding = 20f
        val footerHeight = 60f

        val footerRect = Rectangle(
            pageSize.left + leftPadding,
            pageSize.bottom + bottomPadding,
            pageSize.width - leftPadding - rightPadding,
            footerHeight,
        )

        // Write after normal page content
        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)

        val canvas = Canvas(pdfCanvas, footerRect)

        // Render the footer node's slots
        val footerNode = document.nodes[footerNodeId]
        if (footerNode != null) {
            val elements = registry.renderSlots(footerNode, document, context)
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

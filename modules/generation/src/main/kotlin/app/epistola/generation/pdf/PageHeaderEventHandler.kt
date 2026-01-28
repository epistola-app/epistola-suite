package app.epistola.generation.pdf

import app.epistola.template.model.PageHeaderBlock
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
    private val headerBlock: PageHeaderBlock,
    private val context: RenderContext,
    private val blockRenderers: BlockRendererRegistry,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val pageSize = page.pageSize

        // Create a canvas for the entire page
        val pdfCanvas = PdfCanvas(page.newContentStreamBefore(), page.resources, pdfDoc)

        // Create a layout canvas positioned at the top of the page
        // Reserve space for the header (adjust margins as needed)
        val headerHeight = 100f // Reserve space for header
        val topMargin = pageSize.top - headerHeight

        val canvas = Canvas(pdfCanvas, pageSize)
        canvas.setFixedPosition(
            pageSize.left,
            topMargin,
            pageSize.width,
        )

        // Render header child blocks
        val elements = blockRenderers.renderBlocks(headerBlock.children, context)

        // Add rendered elements to canvas
        for (element in elements) {
            when (element) {
                is IBlockElement -> canvas.add(element)
                is Image -> canvas.add(element)
                // Skip AreaBreak in headers
                is AreaBreak -> {}
            }
        }

        canvas.close()
    }
}

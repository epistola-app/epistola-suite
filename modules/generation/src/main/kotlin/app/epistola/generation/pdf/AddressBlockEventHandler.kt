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
 * Renders address content at absolute page coordinates on page 1.
 * Guarantees exact positioning regardless of page margins.
 */
class AddressBlockEventHandler(
    private val addressNodeId: String,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
) : AbstractPdfDocumentEventHandler() {

    companion object {
        private const val MM_TO_PT = 2.834645f
    }

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val pageSize = page.pageSize

        if (pdfDoc.getPageNumber(page) != 1) return

        val addressNode = document.nodes[addressNodeId] ?: return
        val props = addressNode.props ?: return

        val topPt = ((props["top"] as? Number)?.toFloat() ?: 45f) * MM_TO_PT
        val leftPt = ((props["left"] as? Number)?.toFloat() ?: 20f) * MM_TO_PT
        val widthPt = ((props["addressWidth"] as? Number)?.toFloat() ?: 85f) * MM_TO_PT
        val heightPt = ((props["height"] as? Number)?.toFloat() ?: 45f) * MM_TO_PT

        val rect = Rectangle(leftPt, pageSize.top - topPt - heightPt, widthPt, heightPt)

        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
        val canvas = Canvas(pdfCanvas, rect)

        val slotsByName = addressNode.slots.mapNotNull { slotId ->
            document.slots[slotId]?.let { slot -> slot.name to slot.id }
        }.toMap()

        slotsByName["address"]?.let { slotId ->
            for (element in registry.renderSlot(slotId, document, context)) {
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

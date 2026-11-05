// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.kernel.pdf.tagutils.TagTreePointer
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Div
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

        val align = props["align"] as? String ?: "left"
        val topPt = ((props["top"] as? Number)?.toFloat() ?: 45f) * MM_TO_PT
        val sideDistancePt = ((props["sideDistance"] as? Number)?.toFloat() ?: 20f) * MM_TO_PT
        val widthPt = ((props["addressWidth"] as? Number)?.toFloat() ?: 85f) * MM_TO_PT
        val heightPt = ((props["height"] as? Number)?.toFloat() ?: 45f) * MM_TO_PT

        val leftPt = if (align == "right") {
            pageSize.width - sideDistancePt - widthPt
        } else {
            sideDistancePt
        }
        val rect = Rectangle(leftPt, pageSize.top - topPt - heightPt, widthPt, heightPt)

        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
        // A Canvas does not inherit the Document's default font: without it unstyled text
        // falls back to non-embedded Helvetica, which breaks PDF/A on any non-ASCII glyph.
        val canvas = Canvas(pdfCanvas, rect).setFont(context.fontCache.regular)
        // The recipient address is meaningful content, not decoration: tag it into
        // the structure tree (unlike the header/footer/watermark artifacts) so
        // screen readers announce it (PDF/UA-1, #752).
        canvas.enableAutoTagging(page)

        val slotsByName = addressNode.slots.mapNotNull { slotId ->
            document.slots[slotId]?.let { slot -> slot.name to slot.id }
        }.toMap()

        // One container, so the address is a single structure element that can be placed
        // as a whole (a second top-level element would land at the end of the tree).
        val address = Div()
        slotsByName["address"]?.let { slotId ->
            val childContext = context.withInheritedStylesFrom(addressNode)
            for (element in registry.renderSlot(slotId, document, childContext)) {
                when (element) {
                    is IBlockElement -> address.add(element)
                    is Image -> address.add(element)
                    is AreaBreak -> Unit
                }
            }
        }

        withTaggingFirst(pdfDoc) { canvas.add(address) }

        canvas.close()
        pdfCanvas.release()
    }

    /**
     * Page 1's END_PAGE fires when the document closes, after the whole flow is tagged, so the
     * auto-tagging pointer sits at the end of the tree: the addressee would be read after the
     * body and the signature. The address block is hoisted to the start of the flow
     * ([DirectPdfRenderer.hoistAddressBlock]), so tag the address as the document's first
     * structure element, then put the pointer back where it was.
     */
    private fun withTaggingFirst(pdfDoc: PdfDocument, draw: () -> Unit) {
        val autoPointer = pdfDoc.tagStructureContext.autoTaggingPointer
        val saved = TagTreePointer(autoPointer)
        autoPointer.moveToRoot().setNextNewKidIndex(0)
        try {
            draw()
        } finally {
            autoPointer.moveToPointer(saved)
        }
    }
}

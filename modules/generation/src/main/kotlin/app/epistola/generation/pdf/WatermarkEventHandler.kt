package app.epistola.generation.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.VerticalAlignment
import kotlin.math.sqrt

/**
 * Stamps a single large diagonal watermark ([text], e.g. "PREVIEW") across the
 * centre of every page, drawn *over* the page content on the `END_PAGE` event.
 *
 * Used to mark preview/draft renders so the fast synchronous preview path can't
 * be mistaken for — or abused as — the real generation endpoint. Because it only
 * paints on `END_PAGE`, it never participates in layout and therefore does not
 * shift content or change pagination: a watermarked preview paginates identically
 * to the un-watermarked final.
 *
 * [font] must be the document's content font ([FontCache.regular]) so that, under
 * PDF/A, the watermark text is drawn with the same embedded font and stays
 * PDF/A-2b valid (PDF/A-2 permits the transparency used for the soft fade).
 */
class WatermarkEventHandler(
    private val text: String,
    private val font: PdfFont,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        if (text.isEmpty()) return
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val pageSize = page.pageSize

        // Size the word to span ~75% of the page diagonal so a 45° stamp reads
        // clearly on any page size/orientation without spilling off the edges.
        val diagonal = sqrt(pageSize.width * pageSize.width + pageSize.height * pageSize.height)
        val unitWidth = font.getWidth(text, 1f)
        if (unitWidth <= 0f) return
        val fontSize = TARGET_WIDTH_FRACTION * diagonal / unitWidth

        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
        pdfCanvas.saveState()
        pdfCanvas.setExtGState(PdfExtGState().setFillOpacity(WATERMARK_OPACITY))
        val canvas = Canvas(pdfCanvas, pageSize)
            .setFont(font)
            .setFontSize(fontSize)
            .setFontColor(ColorConstants.GRAY)
        canvas.showTextAligned(
            text,
            pageSize.left + pageSize.width / 2f,
            pageSize.bottom + pageSize.height / 2f,
            TextAlignment.CENTER,
            VerticalAlignment.MIDDLE,
            WATERMARK_ANGLE_RAD,
        )
        canvas.close()
        pdfCanvas.restoreState()
        pdfCanvas.release()
    }

    companion object {
        /** Fraction of the page diagonal the watermark word spans. */
        private const val TARGET_WIDTH_FRACTION = 0.75f

        /** Fill opacity of the watermark (soft, non-obscuring). */
        private const val WATERMARK_OPACITY = 0.12f

        /** Rotation of the watermark, 45° counter-clockwise. */
        private val WATERMARK_ANGLE_RAD = (Math.PI / 4).toFloat()
    }
}

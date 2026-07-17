package app.epistola.generation.pdf

import com.itextpdf.kernel.colors.ColorConstants
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.pdf.canvas.CanvasArtifact
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
 * Stamps a brand/preview watermark ([text], e.g. "Epistola Preview") on every
 * page, drawn *over* the page content on the `END_PAGE` event. Three elements:
 * a small centred line in the top margin (header band), the same in the bottom
 * margin (footer band), and a faint diagonal ghost across the page centre.
 *
 * The design deliberately keeps the document body **very readable**: the bands
 * sit in the page margins clear of body text, and the centre ghost is a soft
 * background wash — a watermark is always discernible, but never competes with
 * the content for legibility.
 *
 * Used to mark preview/draft renders so the fast synchronous preview path can't
 * be mistaken for — or abused as — the real generation endpoint, while doubling
 * as brand awareness. Because it only paints on `END_PAGE`, it never participates
 * in layout and therefore does not shift content or change pagination: a
 * watermarked preview paginates identically to the un-watermarked final.
 *
 * [font] must be the document's content font ([FontCache.regular]) so that, under
 * PDF/A, the watermark text is drawn with the same embedded font and stays
 * PDF/A-2b valid (PDF/A-2 permits the transparency used for the soft fade).
 *
 * The whole stamp is marked as an artifact: renders are tagged and declare
 * PDF/UA-1, which requires every piece of content to be either in the structure
 * tree or explicitly an artifact — and a decorative watermark must never be
 * announced to a screen reader. It also means a watermarked preview's structure
 * tree is identical to the un-watermarked final's, so accessibility checks can
 * run on preview bytes.
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

        val unitWidth = font.getWidth(text, 1f)
        if (unitWidth <= 0f) return

        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)
        pdfCanvas.saveState()
        // try/finally guarantees the marked-content sequence stays balanced and the
        // canvas is released. closeTag() must follow canvas.close(), which is what
        // flushes the layout content into the stream.
        pdfCanvas.openTag(CanvasArtifact())
        try {
            val canvas = Canvas(pdfCanvas, pageSize)
                .setFont(font)
                .setFontColor(ColorConstants.GRAY)

            val centreX = pageSize.left + pageSize.width / 2f

            // Centre ghost first, so the crisper header/footer bands read on top of it.
            // Size it to a modest fraction of the page diagonal — a faint background
            // brand mark, not a page-dominating stamp.
            val diagonal = sqrt(pageSize.width * pageSize.width + pageSize.height * pageSize.height)
            pdfCanvas.setExtGState(PdfExtGState().setFillOpacity(CENTER_OPACITY))
            canvas.setFontSize(CENTER_WIDTH_FRACTION * diagonal / unitWidth)
            canvas.showTextAligned(
                text,
                centreX,
                pageSize.bottom + pageSize.height / 2f,
                TextAlignment.CENTER,
                VerticalAlignment.MIDDLE,
                WATERMARK_ANGLE_RAD,
            )

            // Header and footer bands, in the page margins, clear of body text.
            pdfCanvas.setExtGState(PdfExtGState().setFillOpacity(BAND_OPACITY))
            canvas.setFontSize(WATERMARK_FONT_SIZE)
            canvas.showTextAligned(
                text,
                centreX,
                pageSize.top - EDGE_MARGIN,
                TextAlignment.CENTER,
                VerticalAlignment.MIDDLE,
                0f,
            )
            canvas.showTextAligned(
                text,
                centreX,
                pageSize.bottom + EDGE_MARGIN,
                TextAlignment.CENTER,
                VerticalAlignment.MIDDLE,
                0f,
            )

            canvas.close()
        } finally {
            pdfCanvas.closeTag()
            pdfCanvas.restoreState()
            pdfCanvas.release()
        }
    }

    companion object {
        /** Font size (pt) of the header/footer band text. */
        private const val WATERMARK_FONT_SIZE = 9f

        /** Distance (pt) of the header/footer band from the page edge — inside a typical margin. */
        private const val EDGE_MARGIN = 28f

        /** Fill opacity of the header/footer bands (visible, but out of the body). */
        private const val BAND_OPACITY = 0.45f

        /** Fraction of the page diagonal the faint centre ghost spans. */
        private const val CENTER_WIDTH_FRACTION = 0.40f

        /** Fill opacity of the centre ghost (background-faint; keeps body readable). */
        private const val CENTER_OPACITY = 0.08f

        /** Rotation of the centre ghost, 45° counter-clockwise. */
        private val WATERMARK_ANGLE_RAD = (Math.PI / 4).toFloat()
    }
}

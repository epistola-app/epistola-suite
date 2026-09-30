// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent

/**
 * Per-render state shared by the margins function, which fills [choices] as iText creates each
 * page, and [PageBandEventHandler], which paints when each page ends. [schedule] is set once the
 * body flow is built, before its first page is laid out.
 */
internal class PageBandState {
    val choices = mutableMapOf<Int, PageBandChoice>()
    var schedule: PageBandSchedule? = null
}

/**
 * Paints each page's bands (#1020): the header the schedule chose when the page was created,
 * and the footer it resolves now that the page is complete and every footer drawn on it has
 * recorded its landing. The footer always fits, because the page reserved room for the tallest
 * footer that could still apply to it.
 */
internal class PageBandEventHandler(
    private val state: PageBandState,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val pageNumber = pdfDoc.getPageNumber(page)
        val schedule = state.schedule ?: return

        state.choices[pageNumber]?.header?.let { header ->
            val node = document.nodes[header.nodeId] ?: return@let
            paintHeaderBand(page, pdfDoc, node, header.heightPt, document, header.scope(context), registry)
        }
        schedule.footerOf(pageNumber)?.let { footer ->
            val node = document.nodes[footer.nodeId] ?: return@let
            paintFooterBand(page, pdfDoc, node, footer.heightPt, document, footer.scope(context), registry)
        }
    }
}

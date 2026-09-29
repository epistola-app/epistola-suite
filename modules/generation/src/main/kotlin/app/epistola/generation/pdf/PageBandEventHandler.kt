// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent

/**
 * Paints the header and footer the page-band schedule chose for each page (#1020). [choices]
 * is filled while pages are created, before each page ends, so the painted band is always the
 * one whose height the page reserved.
 */
internal class PageBandEventHandler(
    private val choices: Map<Int, PageBandChoice>,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document
        val choice = choices[pdfDoc.getPageNumber(page)] ?: return

        choice.header?.let { header ->
            val node = document.nodes[header.nodeId] ?: return@let
            paintHeaderBand(page, pdfDoc, node, header.heightPt, document, header.scope(context), registry)
        }
        choice.footer?.let { footer ->
            val node = document.nodes[footer.nodeId] ?: return@let
            paintFooterBand(page, pdfDoc, node, footer.heightPt, document, footer.scope(context), registry)
        }
    }
}

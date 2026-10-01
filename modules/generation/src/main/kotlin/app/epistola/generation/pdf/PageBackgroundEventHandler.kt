// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.pdf.canvas.CanvasArtifact
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent

/**
 * Fills each page with `pageSettings.backgroundColor` (#1027).
 *
 * Painted on `END_PAGE` into a new content stream placed *before* the page's own, so the fill
 * lies beneath everything else on the page and never takes part in layout. It is marked as an
 * artifact: renders are tagged and declare PDF/UA-1, and a background is decoration a screen
 * reader must not announce.
 */
class PageBackgroundEventHandler(
    private val color: Color,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pageSize = page.pageSize

        val canvas = PdfCanvas(page.newContentStreamBefore(), page.resources, docEvent.document)
        canvas.saveState()
        canvas.openTag(CanvasArtifact())
        try {
            canvas.setFillColor(color)
                .rectangle(pageSize.left.toDouble(), pageSize.bottom.toDouble(), pageSize.width.toDouble(), pageSize.height.toDouble())
                .fill()
        } finally {
            canvas.closeTag()
            canvas.restoreState()
            canvas.release()
        }
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.canvas.CanvasArtifact
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.properties.OverflowPropertyValue
import com.itextpdf.layout.properties.Property

/**
 * Event handler that renders a page footer on every page.
 * Registered to handle END_PAGE events and draws footer content at the bottom of each page.
 *
 * [effectiveHeights] maps the footer node id to the band height the caller
 * reserved for it — `max(configured height, measured content height)` — so the
 * drawn rectangle matches the body's bottom margin and tall content is never
 * clipped.
 */
class PageFooterEventHandler(
    private val footerNodeId: String,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
    private val effectiveHeights: Map<String, Float>,
) : AbstractPdfDocumentEventHandler() {

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document

        val footerNode = document.nodes[footerNodeId]
        // Pre-measured effective band height (max of configured and content height),
        // so tall footer content is never clipped.
        val footerHeight = effectiveHeights[footerNodeId]
            ?: parseNodeHeight(footerNode, context)
            ?: context.renderingDefaults.pageFooterHeight

        paintFooterBand(page, pdfDoc, footerNode, footerHeight, document, context, registry)
    }
}

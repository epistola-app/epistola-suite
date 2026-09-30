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
 * Event handler that renders a page header on every page.
 *
 * Accepts an ordered list of header node IDs (size 1 or 2). Mapping is positional:
 *  - 1 header → applies to every page.
 *  - 2 headers → index 0 applies to page 1; index 1 applies to pages 2 and onward.
 *
 * [effectiveHeights] maps each header node id to the band height the caller
 * reserved for it — `max(configured height, measured content height)` — so the
 * drawn rectangle matches the body's top margin and tall content is never
 * clipped. The body's top margin is computed by the caller from the same map.
 */
class PageHeaderEventHandler(
    private val headerNodeIds: List<String>,
    private val document: TemplateDocument,
    private val context: RenderContext,
    private val registry: NodeRendererRegistry,
    private val effectiveHeights: Map<String, Float>,
) : AbstractPdfDocumentEventHandler() {

    init {
        require(headerNodeIds.isNotEmpty() && headerNodeIds.size <= 2) {
            "PageHeaderEventHandler expects 1 or 2 header node IDs, got ${headerNodeIds.size}"
        }
    }

    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
        val docEvent = event as? PdfDocumentEvent ?: return
        val page = docEvent.page ?: return
        val pdfDoc = docEvent.document

        val pageNumber = pdfDoc.getPageNumber(page)
        val selectedId = if (pageNumber == 1 || headerNodeIds.size == 1) {
            headerNodeIds[0]
        } else {
            headerNodeIds[1]
        }
        val headerNode = document.nodes[selectedId] ?: return

        // Pre-measured effective band height (max of configured and content height),
        // so tall header content is never clipped. Falls back to the configured /
        // default height when no measurement was supplied.
        val headerHeight = effectiveHeights[selectedId]
            ?: parseNodeHeight(headerNode, context)
            ?: context.renderingDefaults.pageHeaderHeight

        paintHeaderBand(page, pdfDoc, headerNode, headerHeight, document, context, registry)
    }
}

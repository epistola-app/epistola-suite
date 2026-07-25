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
        val pageSize = page.pageSize

        // --- footer band (bottom of page) ---
        // The footer rectangle's distance to each page edge follows the cascade:
        // footerNode.margin{Bottom,Left,Right} → root.margin{Bottom,Left,Right} →
        // pageSettings.margins.{bottom,left,right} (template > theme > engine defaults).
        val footerNode = document.nodes[footerNodeId]
        val bottomMargin = effectivePageMarginPt(footerNode, "marginBottom", context)
        val leftMargin = effectivePageMarginPt(footerNode, "marginLeft", context)
        val rightMargin = effectivePageMarginPt(footerNode, "marginRight", context)
        // Pre-measured effective band height (max of configured and content height),
        // so tall footer content is never clipped.
        val footerHeight = effectiveHeights[footerNodeId]
            ?: parseNodeHeight(footerNode, context)
            ?: context.renderingDefaults.pageFooterHeight

        val footerRect = Rectangle(
            pageSize.left + leftMargin,
            pageSize.bottom + bottomMargin,
            pageSize.width - leftMargin - rightMargin,
            footerHeight,
        )

        // Write after normal page content
        val pdfCanvas = PdfCanvas(page.newContentStreamAfter(), page.resources, pdfDoc)

        // Mark the footer as an artifact so screen readers skip running content (WCAG PDF14).
        // try/finally guarantees the marked-content sequence is balanced and the
        // canvas released even on the hideOnFirstPage early return.
        pdfCanvas.openTag(CanvasArtifact())
        try {
            val canvas = Canvas(pdfCanvas, footerRect)
            // Safety net: render overflow rather than silently dropping content.
            canvas.setProperty(Property.OVERFLOW_Y, OverflowPropertyValue.VISIBLE)
            canvas.setProperty(Property.OVERFLOW_X, OverflowPropertyValue.VISIBLE)

            // Render the footer node's slots with page-scoped system parameters
            if (footerNode != null) {
                val pageNumber = pdfDoc.getPageNumber(page)
                val hideOnFirstPage = footerNode.props?.get("hideOnFirstPage") == true
                if (hideOnFirstPage && pageNumber == 1) return
                val totalPages = context.totalPages ?: pdfDoc.numberOfPages
                val wrapper = buildBandWrapper(
                    node = footerNode,
                    document = document,
                    baseContext = context,
                    registry = registry,
                    consumedMarginKeys = FOOTER_CONSUMED_MARGINS,
                    componentDefaultsKey = FOOTER_COMPONENT_KEY,
                    pageNumber = pageNumber,
                    totalPages = totalPages,
                )
                canvas.add(wrapper)
            }

            canvas.close()
        } finally {
            pdfCanvas.closeTag()
            pdfCanvas.release()
        }
    }
}

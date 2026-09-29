// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.layout.LayoutContext
import com.itextpdf.layout.layout.LayoutResult
import com.itextpdf.layout.properties.margins.PageMarginBoxes
import com.itextpdf.layout.renderer.AreaBreakRenderer
import com.itextpdf.layout.renderer.DivRenderer
import com.itextpdf.layout.renderer.DocumentRenderer
import com.itextpdf.layout.renderer.DrawContext
import com.itextpdf.layout.renderer.IRenderer

/*
 * The iText side of page headers and footers placed anywhere in the flow (#1020). The behaviour
 * each element relies on is pinned by `PageBandItextProbeTest`.
 */

/**
 * Zero-height element marking where a header or footer sits in the flow. It draws nothing and
 * adds nothing to the structure tree; it only records the page it lands on. The landing is
 * recorded in `draw()`, not `layout()`, because iText may lay an element out on one page and
 * then move it to the next.
 */
internal class PageBandAnchor(val occurrence: PageBandOccurrence) : Div() {
    init {
        setHeight(0f)
        setMargin(0f)
        setPadding(0f)
        accessibilityProperties.role = null
    }

    override fun makeNewRenderer(): IRenderer = PageBandAnchorRenderer(this)
}

private class PageBandAnchorRenderer(private val anchor: PageBandAnchor) : DivRenderer(anchor) {
    override fun draw(drawContext: DrawContext) {
        anchor.occurrence.landingPage = occupiedArea.pageNumber
        super.draw(drawContext)
    }

    override fun getNextRenderer(): IRenderer = PageBandAnchorRenderer(anchor)
}

/**
 * A page break that tells [collector] which section it starts. Its renderer is laid out just
 * before iText creates the new page, so the section is known when that page's bands are chosen.
 */
internal class SectionPageBreak(
    private val section: Int,
    private val collector: PageBandCollector,
) : AreaBreak() {
    override fun makeNewRenderer(): IRenderer = object : AreaBreakRenderer(this) {
        override fun layout(layoutContext: LayoutContext): LayoutResult {
            collector.announceSection(section)
            return super.layout(layoutContext)
        }
    }
}

/**
 * Page margins decided by the page-band schedule instead of measured from margin-box content.
 * Returned per page from `Document.setPageMargins { page -> … }`.
 */
internal class PageBandMargins(
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val left: Float,
) : PageMarginBoxes(emptyList()) {
    override fun layout(documentRenderer: DocumentRenderer, pageNumber: Int, pageSize: Rectangle): FloatArray = floatArrayOf(top, right, bottom, left)
}

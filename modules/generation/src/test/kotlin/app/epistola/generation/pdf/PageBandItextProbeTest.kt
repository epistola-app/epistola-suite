// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEvent
import com.itextpdf.kernel.pdf.event.AbstractPdfDocumentEventHandler
import com.itextpdf.kernel.pdf.event.PdfDocumentEvent
import com.itextpdf.kernel.pdf.tagging.PdfStructElem
import com.itextpdf.layout.Canvas
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.AreaBreak
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.IBlockElement
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.layout.LayoutContext
import com.itextpdf.layout.layout.LayoutResult
import com.itextpdf.layout.properties.UnitValue
import com.itextpdf.layout.properties.margins.PageMarginBoxes
import com.itextpdf.layout.renderer.AreaBreakRenderer
import com.itextpdf.layout.renderer.DivRenderer
import com.itextpdf.layout.renderer.DocumentRenderer
import com.itextpdf.layout.renderer.DrawContext
import com.itextpdf.layout.renderer.IRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Probes the iText 9 behaviour that flow-anchored page headers and footers (#1020) rely on,
 * using plain iText and no Epistola renderer, so a regression on an iText upgrade shows up here
 * rather than as a misplaced header.
 *
 * The design (`docs/plans/flow-anchored-running-headers.md`) needs four things from iText:
 * 1. a per-page top and bottom band, chosen when the page's layout area is created;
 * 2. every anchor drawn on pages before N to be known when page N's band is chosen;
 * 3. a page break, even one nested in a container, to be seen before the page it starts;
 * 4. zero-height anchors that neither move the body nor add structure to a tagged PDF.
 */
class PageBandItextProbeTest {

    /** Everything the probes observe, shared between the elements, the margins function and handlers. */
    private class Sink {
        /** Final landing page per anchor, recorded at draw time. */
        val landings = linkedMapOf<String, Int>()
        val drawCounts = mutableMapOf<String, Int>()

        /** Pages an anchor was laid out on, including trial layouts that were discarded. */
        val layoutPages = mutableMapOf<String, MutableList<Int>>()

        /** Landings visible when the band of each page was chosen. */
        val landingsSeenAtBandChoice = linkedMapOf<Int, Map<String, Int>>()
        val bandChoiceCalls = mutableMapOf<Int, Int>()

        /** Section announced by the most recent page break, consumed by the next band choice. */
        var pendingSection: String? = null
        val sectionByPage = mutableMapOf<Int, String>()
    }

    /** A zero-height flow anchor: records its final page when drawn. */
    private class Anchor(val id: String, private val sink: Sink) : Div() {
        init {
            setHeight(0f)
            setMargin(0f)
            setPadding(0f)
            accessibilityProperties.role = null
        }

        override fun makeNewRenderer(): IRenderer = AnchorRenderer(this, sink)
    }

    private class AnchorRenderer(private val anchor: Anchor, private val sink: Sink) : DivRenderer(anchor) {
        override fun layout(layoutContext: LayoutContext): LayoutResult {
            sink.layoutPages.getOrPut(anchor.id) { mutableListOf() } += layoutContext.area.pageNumber
            return super.layout(layoutContext)
        }

        override fun draw(drawContext: DrawContext) {
            sink.landings[anchor.id] = occupiedArea.pageNumber
            sink.drawCounts.merge(anchor.id, 1, Int::plus)
            super.draw(drawContext)
        }

        override fun getNextRenderer(): IRenderer = AnchorRenderer(anchor, sink)
    }

    /** A page break that announces the section it starts before the new page's band is chosen. */
    private class SectionPageBreak(private val section: String, private val sink: Sink) : AreaBreak() {
        override fun makeNewRenderer(): IRenderer = object : AreaBreakRenderer(this) {
            override fun layout(layoutContext: LayoutContext): LayoutResult {
                sink.pendingSection = section
                return super.layout(layoutContext)
            }
        }
    }

    /** Margin sizes decided by us instead of measured from margin-box content. */
    private class BandMargins(
        private val top: Float,
        private val side: Float,
        private val bottom: Float,
    ) : PageMarginBoxes(emptyList()) {
        override fun layout(documentRenderer: DocumentRenderer, pageNumber: Int, pageSize: Rectangle): FloatArray = floatArrayOf(top, side, bottom, side)
    }

    private fun lines(from: Int, to: Int): List<Paragraph> = (from..to).map { Paragraph("L$it").setFontSize(10f).setMargin(0f) }

    private fun render(
        sink: Sink,
        tagged: Boolean = false,
        band: (page: Int) -> Pair<Float, Float> = { 36f to 36f },
        endPage: ((PdfDocument, Int) -> Unit)? = null,
        content: Document.() -> Unit,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val pdf = PdfDocument(PdfWriter(out))
        if (tagged) pdf.setTagged()
        if (endPage != null) {
            pdf.addEventHandler(
                PdfDocumentEvent.END_PAGE,
                object : AbstractPdfDocumentEventHandler() {
                    override fun onAcceptedEvent(event: AbstractPdfDocumentEvent) {
                        val e = event as PdfDocumentEvent
                        endPage(e.document, e.document.getPageNumber(e.page))
                    }
                },
            )
        }
        val document = Document(pdf, PageSize.A4)
        document.setMargins(36f, 36f, 36f, 36f)
        document.setPageMargins { page: Int ->
            sink.bandChoiceCalls.merge(page, 1, Int::plus)
            sink.landingsSeenAtBandChoice.putIfAbsent(page, sink.landings.toMap())
            sink.pendingSection?.let {
                sink.sectionByPage[page] = it
                sink.pendingSection = null
            }
            val (top, bottom) = band(page)
            BandMargins(top, 36f, bottom)
        }
        document.content()
        document.close()
        return out.toByteArray()
    }

    private fun Document.addAll(elements: List<IBlockElement>) = elements.forEach { add(it) }

    /** Text chunks with their baseline Y, per page. */
    private fun textByPage(pdfBytes: ByteArray): Map<Int, List<Pair<String, Float>>> {
        PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes))).use { pdf ->
            return (1..pdf.numberOfPages).associateWith { pageNumber ->
                val chunks = mutableListOf<Pair<String, Float>>()
                val listener = object : IEventListener {
                    override fun eventOccurred(data: IEventData, type: EventType) {
                        if (type == EventType.RENDER_TEXT) {
                            val info = data as TextRenderInfo
                            chunks += info.text to info.baseline.startPoint[1]
                        }
                    }

                    override fun getSupportedEvents() = setOf(EventType.RENDER_TEXT)
                }
                PdfCanvasProcessor(listener).processPageContent(pdf.getPage(pageNumber))
                chunks
            }
        }
    }

    @Test
    fun `a margins function sets a different top and bottom band on every page`() {
        val sink = Sink()
        val bands = mapOf(1 to (100f to 40f), 2 to (200f to 120f), 3 to (60f to 40f))
        val pdf = render(sink, band = { bands.getValue(it.coerceAtMost(3)) }) {
            addAll(lines(1, 150))
        }

        val pages = textByPage(pdf)
        assertTrue(pages.size >= 3, "expected at least three pages, got ${pages.size}")
        val pageTop = PageSize.A4.top
        for (page in 1..3) {
            val (top, bottom) = bands.getValue(page)
            val baselines = pages.getValue(page).map { it.second }
            val firstBaseline = baselines.max()
            // The first line sits just under the band: its baseline is one line-height below it.
            assertTrue(
                firstBaseline < pageTop - top && firstBaseline > pageTop - top - 20f,
                "page $page: first baseline $firstBaseline should sit just under a ${top}pt band",
            )
            assertTrue(baselines.min() > bottom, "page $page: body text must stay above a ${bottom}pt bottom band")
        }
        // iText caches the chosen margins per page, so the function is consulted once per page.
        assertTrue(sink.bandChoiceCalls.values.all { it == 1 }, "band choice calls per page: ${sink.bandChoiceCalls}")
    }

    @Test
    fun `every anchor drawn before page N is known when page N's band is chosen, and none after`() {
        val sink = Sink()
        render(sink) {
            addAll(lines(1, 20))
            add(Anchor("top-level", sink))
            addAll(lines(21, 90))
            add(Div().add(Paragraph("in div")).add(Anchor("nested-in-div", sink)).add(Paragraph("after")))
            addAll(lines(91, 150))
            val table = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f))).useAllAvailableWidth()
            table.addCell(Cell().add(Paragraph("cell")).add(Anchor("in-table-cell", sink)))
            table.addCell(Cell().add(Paragraph("other")))
            add(table)
            addAll(lines(151, 260))
        }

        assertEquals(setOf("top-level", "nested-in-div", "in-table-cell"), sink.landings.keys)
        assertTrue(sink.drawCounts.values.all { it == 1 }, "each anchor drawn once: ${sink.drawCounts}")
        assertTrue(sink.landings.values.distinct().size > 1, "anchors should span pages: ${sink.landings}")
        for ((page, seen) in sink.landingsSeenAtBandChoice) {
            assertEquals(
                sink.landings.filterValues { it < page },
                seen,
                "at the band choice for page $page",
            )
        }
    }

    @Test
    fun `an anchor in a keep-together block that moves to the next page lands on the page it is drawn on`() {
        val sink = Sink()
        render(sink) {
            addAll(lines(1, 60))
            val block = Div().setKeepTogether(true)
            block.add(Anchor("kept", sink))
            lines(1000, 1030).forEach { block.add(it) }
            add(block)
        }

        assertEquals(2, sink.landings.getValue("kept"))
        assertEquals(1, sink.drawCounts.getValue("kept"))
        assertEquals(emptyMap(), sink.landingsSeenAtBandChoice.getValue(2))
    }

    @Test
    fun `a page break announces its section before the page it starts gets its band, also when nested`() {
        val sink = Sink()
        render(sink) {
            add(Paragraph("cover"))
            add(SectionPageBreak("letter", sink))
            add(Paragraph("letter"))
            add(Div().add(Paragraph("terms follow")).add(SectionPageBreak("terms", sink)).add(Paragraph("terms")))
        }

        assertEquals(mapOf(2 to "letter", 3 to "terms"), sink.sectionByPage)
    }

    @Test
    fun `an anchor at the foot of a full page lands on the next page only when kept with what follows`() {
        val fullPage = linesOnFirstPage()
        val probeSinks = mutableMapOf<Boolean, Sink>()

        fun landingOf(keepWithNext: Boolean): Int {
            val sink = Sink()
            probeSinks[keepWithNext] = sink
            render(sink) {
                addAll(lines(1, fullPage))
                add(Anchor("foot", sink).setKeepWithNext(keepWithNext))
                add(Paragraph("next section").setFontSize(10f).setMargin(0f))
            }
            return sink.landings.getValue("foot")
        }

        assertEquals(1, landingOf(keepWithNext = false))
        assertEquals(2, landingOf(keepWithNext = true))
        // The kept anchor is laid out on page 1 first and discarded there, so a landing recorded
        // at layout time would be wrong; only the draw is final.
        assertEquals(listOf(1, 2), probeSinks.getValue(true).layoutPages.getValue("foot"))
    }

    /** Number of 10pt lines that exactly fill page 1 with the default 36pt bands. */
    private fun linesOnFirstPage(): Int {
        val pdf = render(Sink()) { addAll(lines(1, 200)) }
        return textByPage(pdf).getValue(1).size
    }

    @Test
    fun `zero-height anchors do not move body text`() {
        fun baselines(withAnchors: Boolean): Map<Int, List<Pair<String, Float>>> {
            val sink = Sink()
            return textByPage(
                render(sink) {
                    lines(1, 200).forEachIndexed { index, line ->
                        if (withAnchors && index % 7 == 0) add(Anchor("a$index", sink))
                        add(line)
                    }
                    add(Div().add(Anchor("nested", sink)).add(Paragraph("nested line").setMargin(0f)))
                },
            )
        }

        val plain = baselines(withAnchors = false)
        val anchored = baselines(withAnchors = true)
        assertEquals(plain.keys, anchored.keys)
        for (page in plain.keys) {
            val a = plain.getValue(page)
            val b = anchored.getValue(page)
            assertEquals(a.map { it.first }, b.map { it.first }, "page $page text")
            a.zip(b).forEach { (x, y) -> assertTrue(abs(x.second - y.second) < 0.01f, "page $page: ${x.first} moved") }
        }
    }

    @Test
    fun `anchors add no structure elements to a tagged pdf`() {
        fun structureElementCount(withAnchors: Boolean): Int {
            val sink = Sink()
            val pdf = render(sink, tagged = true) {
                add(Paragraph("one"))
                if (withAnchors) add(Anchor("top", sink))
                add(Div().add(Paragraph("two")).also { if (withAnchors) it.add(Anchor("nested", sink)) })
            }
            PdfDocument(PdfReader(ByteArrayInputStream(pdf))).use { doc ->
                fun count(kids: List<Any?>): Int = kids.filterIsInstance<PdfStructElem>().sumOf { 1 + count(it.kids) }
                return count(doc.structTreeRoot.kids)
            }
        }

        assertEquals(structureElementCount(false), structureElementCount(true))
    }

    @Test
    fun `an end-of-page handler paints what the band choice decided for that page`() {
        val sink = Sink()
        val chosen = mutableMapOf<Int, String>()
        val pdf = render(
            sink,
            band = { page ->
                chosen[page] = sink.sectionByPage[page] ?: chosen[page - 1] ?: "none"
                80f to 36f
            },
            endPage = { doc, page ->
                val canvas = PdfCanvas(doc.getPage(page).newContentStreamAfter(), doc.getPage(page).resources, doc)
                Canvas(canvas, Rectangle(36f, PageSize.A4.top - 70f, 400f, 30f))
                    .add(Paragraph("HEADER ${chosen[page]}").setFontSize(10f))
                    .close()
            },
        ) {
            add(Paragraph("cover"))
            add(SectionPageBreak("letter", sink))
            addAll(lines(1, 120))
        }

        val headers = textByPage(pdf).mapValues { (_, chunks) -> chunks.map { it.first }.filter { it.startsWith("HEADER") } }
        assertEquals(listOf("HEADER none"), headers.getValue(1))
        (2..headers.size).forEach { assertEquals(listOf("HEADER letter"), headers.getValue(it), "page $it") }
    }
}

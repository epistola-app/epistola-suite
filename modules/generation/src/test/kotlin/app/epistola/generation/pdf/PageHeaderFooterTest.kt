// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.geom.Vector
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.canvas.parser.EventType
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor
import com.itextpdf.kernel.pdf.canvas.parser.data.IEventData
import com.itextpdf.kernel.pdf.canvas.parser.data.TextRenderInfo
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageHeaderFooterTest {

    private val renderer = DirectPdfRenderer()

    private fun textNode(id: String, text: String) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(mapOf("type" to "text", "text" to text)),
                    ),
                ),
            ),
        ),
    )

    private fun buildDocument(
        headerProps: Map<String, Any?> = emptyMap(),
        footerProps: Map<String, Any?> = emptyMap(),
        headerStyles: Map<String, Any?>? = null,
        footerStyles: Map<String, Any?>? = null,
        rootStyles: Map<String, Any?>? = null,
        bodyParagraphCount: Int = 30,
    ): TemplateDocument {
        val rootSlotId = "slot-root"
        val headerSlotId = "slot-header"
        val footerSlotId = "slot-footer"

        val bodyNodes = buildBodyNodes(bodyParagraphCount)

        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId), styles = rootStyles),
                "header" to Node(
                    id = "header",
                    type = "pageheader",
                    slots = listOf(headerSlotId),
                    props = headerProps + mapOf("height" to "30pt"),
                    styles = headerStyles,
                ),
                "header-text" to textNode("header-text", "HEADER CONTENT"),
                "footer" to Node(
                    id = "footer",
                    type = "pagefooter",
                    slots = listOf(footerSlotId),
                    props = footerProps + mapOf("height" to "30pt"),
                    styles = footerStyles,
                ),
                "footer-text" to textNode("footer-text", "FOOTER CONTENT"),
            ) + bodyNodes,
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = "root",
                    name = "children",
                    children = listOf("header", "footer") + bodyNodes.keys.toList(),
                ),
                headerSlotId to Slot(id = headerSlotId, nodeId = "header", name = "children", children = listOf("header-text")),
                footerSlotId to Slot(id = footerSlotId, nodeId = "footer", name = "children", children = listOf("footer-text")),
            ),
        )
    }

    private fun buildDocumentWithTwoHeaders(bodyParagraphCount: Int = 30): TemplateDocument {
        val rootSlotId = "slot-root"
        val firstHeaderSlotId = "slot-header-first"
        val restHeaderSlotId = "slot-header-rest"

        val bodyNodes = buildBodyNodes(bodyParagraphCount)

        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "header-first" to Node(
                    id = "header-first",
                    type = "pageheader",
                    slots = listOf(firstHeaderSlotId),
                    props = mapOf("height" to "60pt"),
                ),
                "header-first-text" to textNode("header-first-text", "FIRST PAGE HEADER"),
                "header-rest" to Node(
                    id = "header-rest",
                    type = "pageheader",
                    slots = listOf(restHeaderSlotId),
                    props = mapOf("height" to "30pt"),
                ),
                "header-rest-text" to textNode("header-rest-text", "OTHER PAGES HEADER"),
            ) + bodyNodes,
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = "root",
                    name = "children",
                    children = listOf("header-first", "header-rest") + bodyNodes.keys.toList(),
                ),
                firstHeaderSlotId to Slot(
                    id = firstHeaderSlotId,
                    nodeId = "header-first",
                    name = "children",
                    children = listOf("header-first-text"),
                ),
                restHeaderSlotId to Slot(
                    id = restHeaderSlotId,
                    nodeId = "header-rest",
                    name = "children",
                    children = listOf("header-rest-text"),
                ),
            ),
        )
    }

    private fun buildBodyNodes(bodyParagraphCount: Int): Map<String, Node> {
        val longText = "This is a paragraph of text that is long enough to take up significant vertical space on the page. " +
            "It contains multiple sentences to ensure that the content wraps across several lines in the PDF output. " +
            "We need enough total content to push the document onto at least two pages for testing header and footer behavior."
        return (1..bodyParagraphCount).associate { i ->
            "body-$i" to textNode("body-$i", "$longText (Paragraph $i)")
        }
    }

    private fun renderAndExtract(doc: TemplateDocument): String {
        val output = ByteArrayOutputStream()
        renderer.render(doc, emptyMap(), output)
        return PdfContentExtractor.extract(output.toByteArray())
    }

    @Test
    fun `header renders on all pages by default`() {
        val doc = buildDocument()
        val text = renderAndExtract(doc)

        val headerCount = "HEADER CONTENT".toRegex().findAll(text).count()
        assert(headerCount >= 2) { "Expected header on at least 2 pages, found $headerCount" }
    }

    @Test
    fun `footer hidden on first page when hideOnFirstPage is true`() {
        val doc = buildDocument(footerProps = mapOf("hideOnFirstPage" to true))
        val text = renderAndExtract(doc)

        val pages = text.split("--- PAGE ")
        val page1 = pages.getOrNull(1) ?: ""
        val page2 = pages.getOrNull(2) ?: ""

        assertFalse(page1.contains("FOOTER CONTENT"), "Footer should be hidden on page 1")
        assertContains(page2, "FOOTER CONTENT", message = "Footer should be visible on page 2")
    }

    // -----------------------------------------------------------------------
    // Per-page header variants: 1-header → all pages; 2-headers → first / rest
    // -----------------------------------------------------------------------

    @Test
    fun `first-page header renders on page 1 and other-pages header on subsequent pages`() {
        val doc = buildDocumentWithTwoHeaders()
        val text = renderAndExtract(doc)

        val pages = text.split("--- PAGE ")
        val page1 = pages.getOrNull(1) ?: ""
        val page2 = pages.getOrNull(2) ?: ""

        assertContains(page1, "FIRST PAGE HEADER", message = "First-page header should render on page 1")
        assertFalse(
            page1.contains("OTHER PAGES HEADER"),
            "Other-pages header should not render on page 1",
        )
        assertContains(page2, "OTHER PAGES HEADER", message = "Other-pages header should render on page 2")
        assertFalse(
            page2.contains("FIRST PAGE HEADER"),
            "First-page header should not render on page 2",
        )
    }

    @Test
    fun `two-header single-page document renders only the first-page header`() {
        val doc = buildDocumentWithTwoHeaders(bodyParagraphCount = 1)
        val text = renderAndExtract(doc)

        val pages = text.split("--- PAGE ")
        // Exactly one rendered page (split yields one preamble + one page chunk)
        assertTrue(pages.size <= 2, "Expected a single rendered page, found ${pages.size - 1}")
        val page1 = pages.getOrNull(1) ?: pages[0]

        assertContains(page1, "FIRST PAGE HEADER", message = "First-page header should render on page 1")
        assertFalse(
            page1.contains("OTHER PAGES HEADER"),
            "Other-pages header should never render in a single-page document",
        )
    }

    @Test
    fun `two-header page 2 first body line sits at the running header band, not the tall first-page band`() {
        // First-page header = 200pt; running header = 20pt. The first body line on
        // page 2 should sit just below the running header (~20pt + page margin),
        // not the 200pt cover-page band. Previously the body used max(header heights)
        // for every page, dropping page-2 content ~180pt lower than expected.
        // After this fix, page 1 uses an injected spacer so the cover header has
        // room on page 1 only; pages 2+ start at the running band.
        val doc = buildDocumentTallFirstSmallRunningHeader(bodyParagraphCount = 30)
        val pdfBytes = ByteArrayOutputStream()
            .also { renderer.render(doc, emptyMap(), it) }
            .toByteArray()

        // A4 portrait height = 842pt. Running band ≈ 20pt header + ~57pt page margin ≈ 77pt.
        // First-body-line baseline should be near 842 - 77 ≈ 765 (a bit lower due to
        // text leading). The strict ceiling: must be well above 842 - 200 = 642
        // (which is where it would land if the first-page band leaked onto page 2).
        val firstBodyYPage2 = extractFirstBodyBaselineYOnPage(pdfBytes, 2)
        assertTrue(
            firstBodyYPage2 > 700f,
            "Expected first body line on page 2 to land near the top of body area " +
                "(running header band ≈ 77pt). Got Y=$firstBodyYPage2 — likely the body is " +
                "still being pushed down by the first-page header band.",
        )
    }

    private fun pageCount(pdfBytes: ByteArray): Int {
        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            PdfDocument(reader).use { return it.numberOfPages }
        }
    }

    /**
     * Returns the Y baseline of the first body-text glyph on [pageNumber],
     * skipping the page-decoration text ("FIRST PAGE HEADER" / "OTHER PAGES HEADER").
     */
    private fun extractFirstBodyBaselineYOnPage(pdfBytes: ByteArray, pageNumber: Int): Float {
        val decorationMarkers = listOf("FIRST PAGE HEADER", "OTHER PAGES HEADER", "FOOTER")
        var found: Float? = null
        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            val pdf = PdfDocument(reader)
            val processor = PdfCanvasProcessor(object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data is TextRenderInfo && found == null) {
                        val rendered = data.text.trim()
                        if (rendered.isBlank()) return
                        if (decorationMarkers.any { rendered.contains(it, ignoreCase = true) }) return
                        val origin: Vector = data.baseline.startPoint
                        found = origin.get(Vector.I2)
                    }
                }
                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
            })
            processor.processPageContent(pdf.getPage(pageNumber))
            pdf.close()
        }
        return found ?: error("No body text found on page $pageNumber")
    }

    private fun buildDocumentTallFirstSmallRunningHeader(bodyParagraphCount: Int): TemplateDocument {
        val rootSlotId = "slot-root"
        val firstSlotId = "slot-header-first"
        val restSlotId = "slot-header-rest"
        val bodyNodes = buildPageTaggedBodyNodes(bodyParagraphCount)
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "header-first" to Node(
                    id = "header-first",
                    type = "pageheader",
                    slots = listOf(firstSlotId),
                    props = mapOf("height" to "200pt"),
                ),
                "header-first-text" to textNode("header-first-text", "FIRST PAGE HEADER"),
                "header-rest" to Node(
                    id = "header-rest",
                    type = "pageheader",
                    slots = listOf(restSlotId),
                    props = mapOf("height" to "20pt"),
                ),
                "header-rest-text" to textNode("header-rest-text", "OTHER PAGES HEADER"),
            ) + bodyNodes,
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = "root",
                    name = "children",
                    children = listOf("header-first", "header-rest") + bodyNodes.keys.toList(),
                ),
                firstSlotId to Slot(firstSlotId, "header-first", "children", listOf("header-first-text")),
                restSlotId to Slot(restSlotId, "header-rest", "children", listOf("header-rest-text")),
            ),
        )
    }

    private fun buildDocumentSingleHeader(headerHeightPt: String, bodyParagraphCount: Int): TemplateDocument {
        val rootSlotId = "slot-root"
        val headerSlotId = "slot-header"
        val bodyNodes = buildPageTaggedBodyNodes(bodyParagraphCount)
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "header" to Node(
                    id = "header",
                    type = "pageheader",
                    slots = listOf(headerSlotId),
                    props = mapOf("height" to headerHeightPt),
                ),
                "header-text" to textNode("header-text", "OTHER PAGES HEADER"),
            ) + bodyNodes,
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = "root",
                    name = "children",
                    children = listOf("header") + bodyNodes.keys.toList(),
                ),
                headerSlotId to Slot(headerSlotId, "header", "children", listOf("header-text")),
            ),
        )
    }

    /**
     * Body content with a recognisable marker on the *second* page's first
     * paragraph (`PAGE 2+ BODY`) so the test can measure where that paragraph
     * lands vertically. The first ~12 paragraphs fill page 1; the marker starts
     * page 2.
     */
    private fun buildPageTaggedBodyNodes(bodyParagraphCount: Int): Map<String, Node> {
        val longText = "This is a paragraph of text that is long enough to take up significant vertical space on the page. " +
            "It contains multiple sentences to ensure that the content wraps across several lines in the PDF output. " +
            "We need enough total content to push the document onto at least two pages for testing header and footer behavior."
        return (1..bodyParagraphCount).associate { i ->
            val text = if (i == 13) "PAGE 2+ BODY: $longText (Paragraph $i)" else "$longText (Paragraph $i)"
            "body-$i" to textNode("body-$i", text)
        }
    }

    @Test
    fun `two-header long document keeps first-page header only on page 1`() {
        val doc = buildDocumentWithTwoHeaders(bodyParagraphCount = 60)
        val text = renderAndExtract(doc)

        val pages = text.split("--- PAGE ").drop(1)
        assertTrue(pages.size >= 3, "Expected at least 3 pages, got ${pages.size}")

        assertContains(pages[0], "FIRST PAGE HEADER", message = "First-page header on page 1")
        for ((idx, pageText) in pages.withIndex()) {
            val pageNumber = idx + 1
            if (pageNumber == 1) {
                assertFalse(
                    pageText.contains("OTHER PAGES HEADER"),
                    "Other-pages header must not appear on page 1",
                )
            } else {
                assertFalse(
                    pageText.contains("FIRST PAGE HEADER"),
                    "First-page header must not appear on page $pageNumber",
                )
                assertContains(
                    pageText,
                    "OTHER PAGES HEADER",
                    message = "Other-pages header should appear on page $pageNumber",
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Renderer-level invariants: same shape as PageHeaderCardinalityValidator,
    // re-asserted here so render paths that bypass UpdateDraft (PreviewDocument,
    // PreviewVariant, catalog import, …) can't render with undefined positional
    // semantics on a malformed document.
    // -----------------------------------------------------------------------

    @Test
    fun `renderer rejects pageheader nested below a non-root container`() {
        val rootSlotId = "slot-root"
        val containerSlotId = "slot-container"
        val headerSlotId = "slot-header"
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "container" to Node(id = "container", type = "container", slots = listOf(containerSlotId)),
                "header-misplaced" to Node(
                    id = "header-misplaced",
                    type = "pageheader",
                    slots = listOf(headerSlotId),
                    props = mapOf("height" to "40pt"),
                ),
                "header-text" to textNode("header-text", "MISPLACED HEADER"),
                "body-1" to textNode("body-1", "body"),
            ),
            slots = mapOf(
                rootSlotId to Slot(rootSlotId, "root", "children", listOf("container", "body-1")),
                containerSlotId to Slot(containerSlotId, "container", "children", listOf("header-misplaced")),
                headerSlotId to Slot(headerSlotId, "header-misplaced", "children", listOf("header-text")),
            ),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            renderer.render(doc, emptyMap(), ByteArrayOutputStream())
        }
        assertContains(ex.message ?: "", "header-misplaced")
        assertContains(ex.message ?: "", "direct children of the root slot")
    }

    @Test
    fun `renderer rejects more than two pageheaders even when all are at root`() {
        val rootSlotId = "slot-root"
        fun header(id: String, height: String) = Node(
            id = id,
            type = "pageheader",
            slots = listOf("$id-slot"),
            props = mapOf("height" to height),
        )
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "h1" to header("h1", "30pt"),
                "h2" to header("h2", "30pt"),
                "h3" to header("h3", "30pt"),
                "body-1" to textNode("body-1", "body"),
            ),
            slots = mapOf(
                rootSlotId to Slot(rootSlotId, "root", "children", listOf("h1", "h2", "h3", "body-1")),
                "h1-slot" to Slot("h1-slot", "h1", "children", emptyList()),
                "h2-slot" to Slot("h2-slot", "h2", "children", emptyList()),
                "h3-slot" to Slot("h3-slot", "h3", "children", emptyList()),
            ),
        )

        val ex = assertFailsWith<IllegalArgumentException> {
            renderer.render(doc, emptyMap(), ByteArrayOutputStream())
        }
        assertContains(ex.message ?: "", "at most 2 pageheader")
    }

    // -----------------------------------------------------------------------
    // marginTop / marginBottom override the document page margin fallback
    // -----------------------------------------------------------------------

    @Test
    fun `header marginTop override moves the header relative to a different override`() {
        // Comparing two explicit overrides bypasses the default-fallback
        // arithmetic: a header at 50pt sits 50pt closer to the page top than
        // a header at 100pt, regardless of what the document margin is.
        val nearY = renderHeaderY(headerStyles = mapOf("marginTop" to "50pt"))
        val farY = renderHeaderY(headerStyles = mapOf("marginTop" to "100pt"))

        val delta = nearY - farY
        assertTrue(
            abs(delta - 50f) < 0.5f,
            "Expected header at 100pt to be ~50pt below header at 50pt; near=$nearY, far=$farY, delta=$delta",
        )
    }

    @Test
    fun `footer marginBottom override moves the footer relative to a different override`() {
        val nearY = renderFooterY(footerStyles = mapOf("marginBottom" to "50pt"))
        val farY = renderFooterY(footerStyles = mapOf("marginBottom" to "100pt"))

        val delta = farY - nearY
        assertTrue(
            abs(delta - 50f) < 0.5f,
            "Expected footer at 100pt to be ~50pt above footer at 50pt; near=$nearY, far=$farY, delta=$delta",
        )
    }

    @Test
    fun `header marginTop in sp units is interpreted via the spacing scale`() {
        // 5sp at the default 4pt base unit = 20pt. So a header with marginTop="5sp"
        // should sit at the same Y as one with marginTop="20pt".
        val ptY = renderHeaderY(headerStyles = mapOf("marginTop" to "20pt"))
        val spY = renderHeaderY(headerStyles = mapOf("marginTop" to "5sp"))

        assertTrue(
            abs(ptY - spY) < 0.5f,
            "Expected sp-unit override (5sp = 20pt) to position header same as 20pt; pt=$ptY, sp=$spY",
        )
    }

    @Test
    fun `header without marginTop falls back to the document page margin`() {
        // Default page margin is 20mm ≈ 56.69pt. With no marginTop on the header
        // the header sits at that distance from the page top — same Y as if we
        // explicitly set marginTop to 56.69pt.
        val fallbackY = renderHeaderY()
        val explicitY = renderHeaderY(headerStyles = mapOf("marginTop" to "56.69pt"))

        assertTrue(
            abs(fallbackY - explicitY) < 0.5f,
            "Expected nil marginTop to fall back to document margin (20mm = 56.69pt); fallback=$fallbackY, explicit=$explicitY",
        )
    }

    @Test
    fun `footer without marginBottom falls back to the document page margin`() {
        val fallbackY = renderFooterY()
        val explicitY = renderFooterY(footerStyles = mapOf("marginBottom" to "56.69pt"))

        assertTrue(
            abs(fallbackY - explicitY) < 0.5f,
            "Expected nil marginBottom to fall back to document margin (20mm); fallback=$fallbackY, explicit=$explicitY",
        )
    }

    @Test
    fun `header without marginTop falls back to root marginTop before page margins`() {
        // Root sets marginTop=80pt; header has no marginTop. Header should sit at
        // 80pt (root override) not 56.69pt (the document page margin default).
        val rootOverrideY = renderHeaderY(rootStyles = mapOf("marginTop" to "80pt"))
        val explicitY = renderHeaderY(headerStyles = mapOf("marginTop" to "80pt"))

        assertTrue(
            abs(rootOverrideY - explicitY) < 0.5f,
            "Expected root.marginTop to be used as the header's fallback; root=$rootOverrideY, explicit=$explicitY",
        )
    }

    @Test
    fun `header marginTop wins over root marginTop`() {
        // Header sets marginTop=120pt; root sets marginTop=80pt. Header value wins.
        val headerY = renderHeaderY(
            headerStyles = mapOf("marginTop" to "120pt"),
            rootStyles = mapOf("marginTop" to "80pt"),
        )
        val explicit120 = renderHeaderY(headerStyles = mapOf("marginTop" to "120pt"))

        assertTrue(
            abs(headerY - explicit120) < 0.5f,
            "Expected header.marginTop to override root.marginTop; combined=$headerY, header-only=$explicit120",
        )
    }

    @Test
    fun `header without marginTop uses theme page margin when template has no override`() {
        // Theme provides 50mm top margin; template has no pageSettingsOverride and
        // the header has no marginTop. Header should sit 50mm (≈141.7pt) from
        // the page top — not the engine default of 20mm.
        val themeSettings = app.epistola.template.model.PageSettings(
            format = app.epistola.template.model.PageFormat.A4,
            orientation = app.epistola.template.model.Orientation.portrait,
            margins = app.epistola.template.model.Margins(top = 50, right = 20, bottom = 20, left = 20),
        )
        val doc = buildDocument()
        val pdfBytes = ByteArrayOutputStream()
            .also { renderer.render(doc, emptyMap(), it, resolvedTheme = ResolvedTheme(pageSettings = themeSettings)) }
            .toByteArray()
        val themedY = extractFirstBaselineY(pdfBytes, "HEADER CONTENT")
        // Compare to header rendered with explicit marginTop=141.732pt (50mm in pt).
        val explicitY = renderHeaderY(headerStyles = mapOf("marginTop" to "141.732pt"))
        assertTrue(
            abs(themedY - explicitY) < 1.0f,
            "Expected theme pageSettings.margins.top to be used as fallback; theme=$themedY, explicit=$explicitY",
        )
    }

    @Test
    fun `theme with only top margin set falls through to engine defaults for unset sides`() {
        // Per-side cascade: theme provides only top; left/right/bottom are null and must
        // fall through to the engine defaults (20mm). The header top should sit at the
        // theme value (50mm ≈ 141.7pt) without any NullPointerException from the
        // partial Margins object.
        val themeSettings = app.epistola.template.model.PageSettings(
            format = app.epistola.template.model.PageFormat.A4,
            orientation = app.epistola.template.model.Orientation.portrait,
            margins = app.epistola.template.model.Margins(top = 50, right = null, bottom = null, left = null),
        )
        val doc = buildDocument()
        val pdfBytes = ByteArrayOutputStream()
            .also { renderer.render(doc, emptyMap(), it, resolvedTheme = ResolvedTheme(pageSettings = themeSettings)) }
            .toByteArray()
        val themedY = extractFirstBaselineY(pdfBytes, "HEADER CONTENT")
        // Theme top (50mm) wins.
        val explicitTopY = renderHeaderY(headerStyles = mapOf("marginTop" to "141.732pt"))
        assertTrue(
            abs(themedY - explicitTopY) < 1.0f,
            "Expected theme top (50mm) to apply even when other sides are null; theme=$themedY, explicit=$explicitTopY",
        )
        // Sanity: footer (which would consume bottom from defaults) still renders.
        val footerY = extractFirstBaselineY(pdfBytes, "FOOTER CONTENT")
        assertTrue(footerY > 0f, "Footer should still render with partial theme margins")
    }

    private fun renderHeaderY(
        headerStyles: Map<String, Any?>? = null,
        rootStyles: Map<String, Any?>? = null,
    ): Float {
        val doc = buildDocument(headerStyles = headerStyles, rootStyles = rootStyles)
        val pdfBytes = ByteArrayOutputStream().also { renderer.render(doc, emptyMap(), it) }.toByteArray()
        return extractFirstBaselineY(pdfBytes, "HEADER CONTENT")
    }

    private fun renderFooterY(
        footerStyles: Map<String, Any?>? = null,
        rootStyles: Map<String, Any?>? = null,
    ): Float {
        val doc = buildDocument(footerStyles = footerStyles, rootStyles = rootStyles)
        val pdfBytes = ByteArrayOutputStream().also { renderer.render(doc, emptyMap(), it) }.toByteArray()
        return extractFirstBaselineY(pdfBytes, "FOOTER CONTENT")
    }

    // -----------------------------------------------------------------------
    // Auto-grow: a header/footer band reserves max(configured, content) height,
    // so content taller than the configured height is never clipped away.
    // -----------------------------------------------------------------------

    private val tallMarker = "TALLBANDMARKER"

    /** A header/footer content node that wraps to many lines — clearly taller than a small band. */
    private fun tallContentNode(id: String) = textNode(id, "$tallMarker " + "word ".repeat(160))

    private fun buildSingleBandDoc(
        bandType: String,
        bandHeight: String,
        contentNode: Node,
        bodyParagraphCount: Int = 6,
    ): TemplateDocument {
        val rootSlotId = "slot-root"
        val bandSlotId = "slot-band"
        val longText = "This is a paragraph of text that is long enough to take up significant vertical space. " +
            "It contains multiple sentences so the content wraps across several lines in the PDF output."
        val bodyNodes = (1..bodyParagraphCount).associate { i ->
            val t = if (i == 1) "BODYSTART $longText" else "$longText (p$i)"
            "body-$i" to textNode("body-$i", t)
        }
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "band" to Node(
                    id = "band",
                    type = bandType,
                    slots = listOf(bandSlotId),
                    props = mapOf("height" to bandHeight),
                ),
                contentNode.id to contentNode,
            ) + bodyNodes,
            slots = mapOf(
                rootSlotId to Slot(rootSlotId, "root", "children", listOf("band") + bodyNodes.keys.toList()),
                bandSlotId to Slot(bandSlotId, "band", "children", listOf(contentNode.id)),
            ),
        )
    }

    private fun bodyStartY(doc: TemplateDocument): Float {
        val pdfBytes = ByteArrayOutputStream().also { renderer.render(doc, emptyMap(), it) }.toByteArray()
        return extractFirstBaselineYOnPage(pdfBytes, "BODYSTART", 1)
    }

    @Test
    fun `header content taller than the configured height still renders (not clipped)`() {
        // Reproduces the reported bug: a header whose content (letterhead, address
        // block, many lines) exceeds the configured height used to be dropped by
        // iText, leaving a blank band. The band must now grow to fit the content.
        val doc = buildSingleBandDoc("pageheader", "10pt", tallContentNode("band-text"))
        val text = renderAndExtract(doc)
        assertContains(
            text,
            tallMarker,
            message = "Header content taller than the 10pt configured height must still render (band auto-grows), not be clipped away",
        )
    }

    @Test
    fun `footer content taller than the configured height still renders (not clipped)`() {
        val doc = buildSingleBandDoc("pagefooter", "10pt", tallContentNode("band-text"))
        val text = renderAndExtract(doc)
        assertContains(
            text,
            tallMarker,
            message = "Footer content taller than the configured height must still render (band auto-grows)",
        )
    }

    @Test
    fun `header band auto-grows so the body clears tall content`() {
        // Same tiny configured height; only the content height differs. With a
        // short header the body sits high; with tall content the band grows and
        // pushes the body well down. Pre-fix both used the 10pt band (content
        // clipped) and the body sat at the same Y.
        val shortY = bodyStartY(buildSingleBandDoc("pageheader", "10pt", textNode("band-text", "SHORT HEADER")))
        val tallY = bodyStartY(buildSingleBandDoc("pageheader", "10pt", tallContentNode("band-text")))
        assertTrue(
            tallY < shortY - 40f,
            "Tall header content should push the body down because the band auto-grows; shortY=$shortY tallY=$tallY",
        )
    }

    @Test
    fun `configured height is honoured as a minimum when taller than the content`() {
        // A configured height larger than the content must still reserve that space
        // (height is a minimum, not an exact clip).
        val smallY = bodyStartY(buildSingleBandDoc("pageheader", "10pt", textNode("band-text", "SHORT HEADER")))
        val bigY = bodyStartY(buildSingleBandDoc("pageheader", "150pt", textNode("band-text", "SHORT HEADER")))
        assertTrue(
            bigY < smallY - 100f,
            "A configured height larger than content must still reserve that space; smallY=$smallY bigY=$bigY",
        )
    }

    @Test
    fun `auto-grow works on the two-pass render path`() {
        // A `sys.pages.total` expression forces TWO-PASS rendering (page count is
        // resolved in a first pass). The tall header must still auto-grow and render
        // through that path — the effective heights are threaded into both passes, not
        // just the single-pass path.
        val rootSlot = "slot-root"
        val headerSlot = "slot-header"
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlot)),
                "header" to Node(id = "header", type = "pageheader", slots = listOf(headerSlot), props = mapOf("height" to "10pt")),
                "header-text" to tallContentNode("header-text"),
                "body-text" to textNode("body-text", "Body content."),
                "pages" to expressionTextNode("pages", "sys.pages.total"),
            ),
            slots = mapOf(
                rootSlot to Slot(rootSlot, "root", "children", listOf("header", "body-text", "pages")),
                headerSlot to Slot(headerSlot, "header", "children", listOf("header-text")),
            ),
        )
        assertTrue(TwoPassAnalyzer.requiresTwoPassRendering(doc), "test doc must take the two-pass path")
        val text = renderAndExtract(doc)
        assertContains(
            text,
            tallMarker,
            message = "Tall header content taller than the 10pt height must still render via the two-pass path",
        )
    }

    private fun expressionTextNode(id: String, expression: String) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(mapOf("type" to "expression", "attrs" to mapOf("expression" to expression))),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `address block authored inside a header does not inflate the header band`() {
        // An address block is a page-absolute element: its window is drawn absolutely
        // and an in-flow spacer reserves its height in the BODY. Whether it is authored
        // in the body or nested inside a page header, it must hoist to the body root and
        // the header band must NOT reserve its (~200pt) window height. So a document with
        // the address in the header must render the body at the same Y as one with the
        // address in the body — the two are identical after hoisting.
        val marker = "BODYMARKER"
        val inBody = bodyMarkerY(buildHeaderWithAddressDoc(addressInHeader = false), marker)
        val inHeader = bodyMarkerY(buildHeaderWithAddressDoc(addressInHeader = true), marker)
        assertTrue(
            abs(inBody - inHeader) < 1.0f,
            "Address block in a header must hoist like a body address and not inflate the band; bodyY=$inBody headerY=$inHeader",
        )
    }

    private fun bodyMarkerY(doc: TemplateDocument, marker: String): Float {
        val pdfBytes = ByteArrayOutputStream().also { renderer.render(doc, emptyMap(), it) }.toByteArray()
        return extractFirstBaselineYOnPage(pdfBytes, marker, 1)
    }

    private fun buildHeaderWithAddressDoc(addressInHeader: Boolean): TemplateDocument {
        val rootSlot = "slot-root"
        val headerSlot = "slot-header"
        val addrSlot = "slot-addr"
        val asideSlot = "slot-aside"
        val addressNode = Node(
            id = "addressblock",
            type = "addressblock",
            slots = listOf(addrSlot, asideSlot),
            props = mapOf("top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45),
        )
        val headerChildren = if (addressInHeader) listOf("header-text", "addressblock") else listOf("header-text")
        val rootChildren = if (addressInHeader) listOf("header", "body-text") else listOf("header", "addressblock", "body-text")
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlot)),
                "header" to Node(id = "header", type = "pageheader", slots = listOf(headerSlot)),
                "header-text" to textNode("header-text", "HDR"),
                "addressblock" to addressNode,
                "addr-text" to textNode("addr-text", "ADDR"),
                "aside-text" to textNode("aside-text", "ASIDE"),
                "body-text" to textNode("body-text", "BODYMARKER content"),
            ),
            slots = mapOf(
                rootSlot to Slot(rootSlot, "root", "children", rootChildren),
                headerSlot to Slot(headerSlot, "header", "children", headerChildren),
                addrSlot to Slot(addrSlot, "addressblock", "address", listOf("addr-text")),
                asideSlot to Slot(asideSlot, "addressblock", "aside", listOf("aside-text")),
            ),
        )
    }

    @Test
    fun `address reservation respects the header height and shrinks to zero under a tall header`() {
        // The address window bottom is at top+height = 45+45mm = 255pt. A header taller
        // than that already pushes body content below the window, so the address block
        // must reserve NO extra space: the body lands at the same Y with or without an
        // address block. (Before this fix the reservation used the raw header height, so
        // a tall auto-grown header still over-reserved ~the full window height.)
        val withAddr = bodyMarkerY(buildTallHeaderDoc(withAddress = true), "BODYMARKER")
        val withoutAddr = bodyMarkerY(buildTallHeaderDoc(withAddress = false), "BODYMARKER")
        assertTrue(
            abs(withAddr - withoutAddr) < 1.0f,
            "Under a header taller than the address window, an address block must add no reservation; with=$withAddr without=$withoutAddr",
        )
    }

    private fun buildTallHeaderDoc(withAddress: Boolean): TemplateDocument {
        val rootSlot = "slot-root"
        val headerSlot = "slot-header"
        val addrSlot = "slot-addr"
        val asideSlot = "slot-aside"
        // Header content wraps to ~22 lines → effective band well over the 255pt window.
        val tallHeaderText = "word ".repeat(220)
        val nodes = mutableMapOf(
            "root" to Node(id = "root", type = "root", slots = listOf(rootSlot)),
            "header" to Node(id = "header", type = "pageheader", slots = listOf(headerSlot)),
            "header-text" to textNode("header-text", tallHeaderText),
            "body-text" to textNode("body-text", "BODYMARKER content"),
        )
        val rootChildren = mutableListOf("header")
        val slots = mutableMapOf(
            rootSlot to Slot(rootSlot, "root", "children", rootChildren),
            headerSlot to Slot(headerSlot, "header", "children", listOf("header-text")),
        )
        if (withAddress) {
            nodes["addressblock"] = Node(
                id = "addressblock",
                type = "addressblock",
                slots = listOf(addrSlot, asideSlot),
                props = mapOf("top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45),
            )
            nodes["addr-text"] = textNode("addr-text", "ADDR")
            rootChildren.add("addressblock")
            // Empty aside so the asideDiv carries only the (now zero) reservation.
            slots[addrSlot] = Slot(addrSlot, "addressblock", "address", listOf("addr-text"))
            slots[asideSlot] = Slot(asideSlot, "addressblock", "aside", emptyList())
        }
        rootChildren.add("body-text")
        return TemplateDocument(root = "root", nodes = nodes, slots = slots)
    }

    private fun extractFirstBaselineY(pdfBytes: ByteArray, text: String): Float = extractFirstBaselineYOnPage(pdfBytes, text, 1)

    private fun extractFirstBaselineYOnPage(pdfBytes: ByteArray, text: String, pageNumber: Int): Float {
        var found: Float? = null
        PdfReader(ByteArrayInputStream(pdfBytes)).use { reader ->
            val pdf = PdfDocument(reader)
            val processor = PdfCanvasProcessor(object : IEventListener {
                override fun eventOccurred(data: IEventData?, type: EventType?) {
                    if (data is TextRenderInfo && found == null) {
                        val rendered = data.text.trim()
                        if (rendered.contains(text)) {
                            val origin: Vector = data.baseline.startPoint
                            found = origin.get(Vector.I2)
                        }
                    }
                }
                override fun getSupportedEvents(): Set<EventType> = setOf(EventType.RENDER_TEXT)
            })
            processor.processPageContent(pdf.getPage(pageNumber))
            pdf.close()
        }
        return found ?: error("Text '$text' not found on page $pageNumber")
    }
}

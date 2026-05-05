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
    ): TemplateDocument {
        val rootSlotId = "slot-root"
        val headerSlotId = "slot-header"
        val footerSlotId = "slot-footer"

        // Create enough content for 2+ pages
        val longText = "This is a paragraph of text that is long enough to take up significant vertical space on the page. " +
            "It contains multiple sentences to ensure that the content wraps across several lines in the PDF output. " +
            "We need enough total content to push the document onto at least two pages for testing header and footer behavior."
        val bodyNodes = (1..30).associate { i ->
            "body-$i" to textNode("body-$i", "$longText (Paragraph $i)")
        }

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
    fun `header hidden on first page when hideOnFirstPage is true`() {
        val doc = buildDocument(headerProps = mapOf("hideOnFirstPage" to true))
        val text = renderAndExtract(doc)

        // Page 1 should NOT have header, page 2+ should
        val pages = text.split("--- PAGE ")
        val page1 = pages.getOrNull(1) ?: ""
        val page2 = pages.getOrNull(2) ?: ""

        assertFalse(page1.contains("HEADER CONTENT"), "Header should be hidden on page 1")
        assertContains(page2, "HEADER CONTENT", message = "Header should be visible on page 2")
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

    @Test
    fun `header and footer both hidden on first page`() {
        val doc = buildDocument(
            headerProps = mapOf("hideOnFirstPage" to true),
            footerProps = mapOf("hideOnFirstPage" to true),
        )
        val text = renderAndExtract(doc)

        val pages = text.split("--- PAGE ")
        val page1 = pages.getOrNull(1) ?: ""

        assertFalse(page1.contains("HEADER CONTENT"), "Header should be hidden on page 1")
        assertFalse(page1.contains("FOOTER CONTENT"), "Footer should be hidden on page 1")
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

    private fun extractFirstBaselineY(pdfBytes: ByteArray, text: String): Float {
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
            processor.processPageContent(pdf.getPage(1))
            pdf.close()
        }
        return found ?: error("Text '$text' not found in rendered PDF")
    }
}

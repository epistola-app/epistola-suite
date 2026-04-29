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
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
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
    // marginTop / marginBottom override the page-padding defaults
    // -----------------------------------------------------------------------

    @Test
    fun `header marginTop overrides page-header padding default`() {
        // Default pageHeaderPadding is 20pt (RenderingDefaults.V1).
        // Setting marginTop on the header to 50pt should push the header DOWN
        // by 30pt (50 - 20) compared to the default.
        val baselineY = renderHeaderY()
        val overrideY = renderHeaderY(headerStyles = mapOf("marginTop" to "50pt"))

        val delta = baselineY - overrideY
        assertTrue(
            abs(delta - 30f) < 0.5f,
            "Expected header to move down by ~30pt; baseline=$baselineY, override=$overrideY, delta=$delta",
        )
    }

    @Test
    fun `footer marginBottom overrides page-footer padding default`() {
        // Default pageFooterPadding is 20pt. Setting marginBottom to 50pt
        // should push the footer UP by 30pt.
        val baselineY = renderFooterY()
        val overrideY = renderFooterY(footerStyles = mapOf("marginBottom" to "50pt"))

        val delta = overrideY - baselineY
        assertTrue(
            abs(delta - 30f) < 0.5f,
            "Expected footer to move up by ~30pt; baseline=$baselineY, override=$overrideY, delta=$delta",
        )
    }

    @Test
    fun `header marginTop in sp units is interpreted via spacing scale`() {
        // 5sp at the default 4pt base unit = 20pt — same as the default
        // pageHeaderPadding, so the header should sit at the same Y position
        // as the no-override baseline.
        val baselineY = renderHeaderY()
        val spY = renderHeaderY(headerStyles = mapOf("marginTop" to "5sp"))

        assertTrue(
            abs(baselineY - spY) < 0.5f,
            "Expected sp-unit override (5sp = 20pt) to match default pageHeaderPadding; baseline=$baselineY, with-sp=$spY",
        )
    }

    private fun renderHeaderY(headerStyles: Map<String, Any?>? = null): Float {
        val doc = buildDocument(headerStyles = headerStyles)
        val pdfBytes = ByteArrayOutputStream().also { renderer.render(doc, emptyMap(), it) }.toByteArray()
        return extractFirstBaselineY(pdfBytes, "HEADER CONTENT")
    }

    private fun renderFooterY(footerStyles: Map<String, Any?>? = null): Float {
        val doc = buildDocument(footerStyles = footerStyles)
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

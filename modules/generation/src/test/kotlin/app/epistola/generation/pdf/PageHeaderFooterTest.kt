package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

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
                ),
                "header-text" to textNode("header-text", "HEADER CONTENT"),
                "footer" to Node(
                    id = "footer",
                    type = "pagefooter",
                    slots = listOf(footerSlotId),
                    props = footerProps + mapOf("height" to "30pt"),
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
}

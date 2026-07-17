package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Per-feature WCAG / PDF-UA-1 accessibility tests. Each renders a document and
 * reads the produced PDF back with [PdfAccessibilityInspector] to assert the
 * accessibility behavior added on `feat/wcag` (commit 2ca40e4d).
 *
 * Full PDF/UA-1 conformance validation (veraPDF) is intentionally out of scope
 * here and tracked separately as an opt-in test profile.
 */
class PdfAccessibilityTest {

    private val renderer = DirectPdfRenderer()

    /** Stand-in for the preview watermark; the real text is DocumentPreviewRenderer's concern. */
    private val watermark = "Epistola Preview"

    // ---------------------------------------------------------------------------
    // Document-level metadata
    // ---------------------------------------------------------------------------

    @Test
    fun `tagged PDF with language, doc-title preference, PDF-UA marker and page labels`() {
        val doc = documentWithChildren(
            mapOf("h1" to headingNode("h1", "Report", 1), "p1" to textNode("p1", "Body.")),
            listOf("h1", "p1"),
        )

        val snapshot = PdfAccessibilityInspector.inspect(
            renderToBytes(doc, metadata = PdfMetadata(title = "Quarterly Report")),
        )

        assertTrue(snapshot.tagged, "PDF should be tagged")
        assertEquals("nl-NL", snapshot.lang, "default catalog /Lang")
        assertTrue(snapshot.displayDocTitle, "DisplayDocTitle viewer preference set when a title is present")
        assertTrue(snapshot.xmp.contains("pdfuaid:part"), "XMP should carry the PDF/UA-1 identifier")
        assertEquals("1", snapshot.pageLabels.firstOrNull(), "first page label is decimal 1")
    }

    @Test
    fun `custom document language is written to the catalog`() {
        val doc = documentWithChildren(mapOf("p1" to textNode("p1", "Hello.")), listOf("p1"))

        val snapshot = PdfAccessibilityInspector.inspect(
            renderToBytes(doc, metadata = PdfMetadata(language = "en-US")),
        )

        assertEquals("en-US", snapshot.lang)
    }

    // ---------------------------------------------------------------------------
    // Headings
    // ---------------------------------------------------------------------------

    @Test
    fun `headings are tagged H1-H3 in document order`() {
        val doc = documentWithChildren(
            mapOf(
                "h1" to headingNode("h1", "Title", 1),
                "p1" to textNode("p1", "Intro."),
                "h2" to headingNode("h2", "Section", 2),
                "h3" to headingNode("h3", "Subsection", 3),
            ),
            listOf("h1", "p1", "h2", "h3"),
        )

        val snapshot = PdfAccessibilityInspector.inspect(renderToBytes(doc))

        val headingRoles = snapshot.structRoles.filter { it.matches(Regex("H[1-6]")) }
        assertEquals(listOf("H1", "H2", "H3"), headingRoles)
    }

    // ---------------------------------------------------------------------------
    // Outline / bookmarks (incl. the page-resolution fix)
    // ---------------------------------------------------------------------------

    @Test
    fun `outline is nested by level and bookmarks resolve to the heading's actual page`() {
        // h1 on page 1; a page break pushes the level-2 heading onto page 2.
        val doc = documentWithChildren(
            mapOf(
                "h1" to headingNode("h1", "Main Title", 1),
                "pb" to Node(id = "pb", type = "pagebreak"),
                "h2" to headingNode("h2", "Section Header", 2),
            ),
            listOf("h1", "pb", "h2"),
        )

        val outline = PdfAccessibilityInspector.inspect(renderToBytes(doc)).outline

        assertEquals(2, outline.size, "one bookmark per heading")
        assertEquals("Main Title", outline[0].title)
        assertEquals(0, outline[0].depth)
        assertEquals(1, outline[0].page, "first heading is on page 1")

        assertEquals("Section Header", outline[1].title)
        assertEquals(1, outline[1].depth, "level-2 heading nests under the level-1 heading")
        assertEquals(2, outline[1].page, "bookmark resolves to the heading's real page, not page 1")
    }

    // ---------------------------------------------------------------------------
    // Links
    // ---------------------------------------------------------------------------

    @Test
    fun `hyperlink exposes an accessible description`() {
        val doc = documentWithChildren(
            mapOf("p1" to linkParagraphNode("p1", "Visit our website", "https://example.com")),
            listOf("p1"),
        )

        val snapshot = PdfAccessibilityInspector.inspect(renderToBytes(doc))

        assertTrue(
            snapshot.alts().contains("Visit our website"),
            "link alternate description should be present; alts=${snapshot.alts()}",
        )
    }

    // ---------------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------------

    @Test
    fun `image alt text is exposed as a tagged figure description`() {
        val doc = documentWithImageNode(mapOf("assetId" to "asset-123", "alt" to "Company logo"))

        val snapshot = PdfAccessibilityInspector.inspect(renderToBytes(doc, resolver = resolverWithTestPng))

        assertTrue(snapshot.roleCount("Figure") >= 1, "image should be a Figure structure element")
        assertTrue(
            snapshot.alts().contains("Company logo"),
            "image alt text should be the figure's description; alts=${snapshot.alts()}",
        )
    }

    @Test
    fun `decorative image is an artifact and absent from the structure tree`() {
        val doc = documentWithImageNode(
            mapOf("assetId" to "asset-123", "alt" to "ignored", "decorative" to true),
        )

        val snapshot = PdfAccessibilityInspector.inspect(renderToBytes(doc, resolver = resolverWithTestPng))

        assertTrue(snapshot.tagged)
        assertEquals(0, snapshot.roleCount("Figure"), "decorative image must not be a Figure")
        assertTrue(!snapshot.alts().contains("ignored"), "decorative image must not expose alt text")
    }

    // ---------------------------------------------------------------------------
    // Tables
    // ---------------------------------------------------------------------------

    @Test
    fun `table header row cells are tagged TH and data cells TD`() {
        val doc = tableDocument(rows = 2, columns = 2, headerRows = 1)

        val snapshot = PdfAccessibilityInspector.inspect(renderToBytes(doc))

        assertEquals(2, snapshot.roleCount("TH"), "two header cells")
        assertEquals(2, snapshot.roleCount("TD"), "two data cells")
    }

    @Test
    fun `datatable header cells are tagged TH`() {
        val doc = datatableDocument()
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "Widget", "qty" to 10),
                mapOf("name" to "Gadget", "qty" to 3),
            ),
        )

        val snapshot = PdfAccessibilityInspector.inspect(renderToBytes(doc, data))

        assertEquals(2, snapshot.roleCount("TH"), "one TH per declared column header")
        assertTrue(snapshot.roleCount("TD") >= 1, "data rows produce TD cells")
    }

    // ---------------------------------------------------------------------------
    // Page header / footer artifacts
    // ---------------------------------------------------------------------------

    @Test
    fun `page header and footer are drawn but marked as artifacts`() {
        val doc = headerFooterDocument(bodyParagraphs = 3)
        val pdfBytes = renderToBytes(doc)

        val visibleText = PdfContentExtractor.extract(pdfBytes)
        assertTrue(visibleText.contains("HEADER CONTENT"), "header is still drawn")
        assertTrue(visibleText.contains("FOOTER CONTENT"), "footer is still drawn")

        val snapshot = PdfAccessibilityInspector.inspect(pdfBytes)
        assertTrue(snapshot.tagged)
        assertEquals(
            3,
            snapshot.roleCount("P"),
            "only the 3 body paragraphs are structure content; header/footer are artifacts",
        )
    }

    // ---------------------------------------------------------------------------
    // Watermark artifact
    // ---------------------------------------------------------------------------

    @Test
    fun `preview watermark is drawn but marked as an artifact`() {
        val pdfBytes = renderToBytes(watermarkableDocument(), watermarkText = watermark)

        // Artifact content is still extractable, so this guards the visible watermark.
        assertTrue(
            PdfContentExtractor.extract(pdfBytes).contains(watermark),
            "watermark is still drawn",
        )

        val artifactText = PdfMarkedContentInspector.artifactText(pdfBytes)
        assertTrue(
            artifactText.any { it.contains(watermark) },
            "watermark must be inside an /Artifact marked-content sequence; artifacts=$artifactText",
        )
    }

    @Test
    fun `watermarked render draws no content outside the structure tree or an artifact`() {
        val pdfBytes = renderToBytes(watermarkableDocument(), watermarkText = watermark)

        assertTrue(PdfAccessibilityInspector.inspect(pdfBytes).tagged)
        assertEquals(
            emptyList(),
            PdfMarkedContentInspector.unmarkedText(pdfBytes),
            "a tagged render declaring PDF/UA-1 must draw no unmarked content",
        )
    }

    @Test
    fun `watermarked render has the same structure tree as the shipped document`() {
        val doc = watermarkableDocument()

        val shipped = PdfAccessibilityInspector.inspect(renderToBytes(doc))
        val preview = PdfAccessibilityInspector.inspect(renderToBytes(doc, watermarkText = watermark))

        assertEquals(
            shipped.structRoles,
            preview.structRoles,
            "watermark must not add anything to the structure tree — this is what lets " +
                "accessibility checks run on preview bytes",
        )
    }

    private fun watermarkableDocument() = documentWithChildren(
        mapOf("h1" to headingNode("h1", "Report", 1), "p1" to textNode("p1", "Body.")),
        listOf("h1", "p1"),
    )

    // ---------------------------------------------------------------------------
    // Rendering helper
    // ---------------------------------------------------------------------------

    private fun renderToBytes(
        doc: TemplateDocument,
        data: Map<String, Any?> = emptyMap(),
        metadata: PdfMetadata = PdfMetadata(),
        resolver: AssetResolver? = null,
        watermarkText: String? = null,
    ): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(
            doc,
            data,
            output,
            metadata = metadata,
            assetResolver = resolver,
            watermarkText = watermarkText,
        )
        return output.toByteArray()
    }

    private val resolverWithTestPng = AssetResolver { assetId, _ ->
        if (assetId == "asset-123") AssetResolution(content = testPngBytes, mimeType = "image/png") else null
    }

    /** Minimal valid 1x1 PNG (67 bytes) — same bytes used by ImageNodeRendererTest. */
    private val testPngBytes: ByteArray = run {
        val header = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val ihdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
        )
        val idat = byteArrayOf(
            0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
            0x08, 0xD7.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(), 0x00, 0x00,
            0x00, 0x02, 0x00, 0x01, 0xE2.toByte(), 0x21, 0xBC.toByte(), 0x33,
        )
        val iend = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
        header + ihdr + idat + iend
    }

    // ---------------------------------------------------------------------------
    // Document builders (mirrors the patterns in the other generation tests)
    // ---------------------------------------------------------------------------

    private fun textNode(id: String, text: String) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text))),
                ),
            ),
        ),
    )

    private fun headingNode(id: String, text: String, level: Int) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "heading",
                        "attrs" to mapOf("level" to level),
                        "content" to listOf(mapOf("type" to "text", "text" to text)),
                    ),
                ),
            ),
        ),
    )

    private fun linkParagraphNode(id: String, text: String, href: String) = Node(
        id = id,
        type = "text",
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(
                            mapOf(
                                "type" to "text",
                                "text" to text,
                                "marks" to listOf(
                                    mapOf("type" to "link", "attrs" to mapOf("href" to href)),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun documentWithChildren(
        childNodes: Map<String, Node>,
        childNodeIds: List<String>,
        extraSlots: Map<String, Slot> = emptyMap(),
    ): TemplateDocument {
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        return TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(rootNodeId to Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))) + childNodes,
            slots = mapOf(
                rootSlotId to Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = childNodeIds),
            ) + extraSlots,
        )
    }

    private fun documentWithImageNode(imageProps: Map<String, Any?>): TemplateDocument {
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val imageNodeId = "image-1"
        return TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId)),
                imageNodeId to Node(id = imageNodeId, type = "image", props = imageProps),
            ),
            slots = mapOf(
                rootSlotId to Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf(imageNodeId)),
            ),
        )
    }

    private fun tableDocument(rows: Int, columns: Int, headerRows: Int): TemplateDocument {
        val tableNodeId = "table1"
        val nodes = mutableMapOf<String, Node>()
        val slots = mutableMapOf<String, Slot>()
        val tableSlotIds = mutableListOf<String>()
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val slotId = "slot-$row-$col"
                val textId = "t-$row-$col"
                tableSlotIds.add(slotId)
                nodes[textId] = textNode(textId, "R${row}C$col")
                slots[slotId] = Slot(id = slotId, nodeId = tableNodeId, name = "cell-$row-$col", children = listOf(textId))
            }
        }
        nodes[tableNodeId] = Node(
            id = tableNodeId,
            type = "table",
            slots = tableSlotIds,
            props = mapOf("rows" to rows, "columns" to columns, "headerRows" to headerRows),
        )
        return documentWithChildren(nodes, listOf(tableNodeId), slots)
    }

    private fun datatableDocument(): TemplateDocument {
        val datatableNodeId = "datatable1"
        val columnsSlotId = "slot-columns"
        val nodes = mutableMapOf<String, Node>()
        val slots = mutableMapOf<String, Slot>()
        val columnDefs = listOf("col-0" to ("Product" to "item.name"), "col-1" to ("Qty" to "item.qty"))
        val columnNodeIds = mutableListOf<String>()
        for ((colId, def) in columnDefs) {
            val (header, bodyExpr) = def
            val bodySlotId = "slot-body-$colId"
            val bodyTextId = "body-$colId"
            nodes[bodyTextId] = textNode(bodyTextId, "{{$bodyExpr}}")
            slots[bodySlotId] = Slot(id = bodySlotId, nodeId = colId, name = "body", children = listOf(bodyTextId))
            nodes[colId] = Node(
                id = colId,
                type = "datatable-column",
                slots = listOf(bodySlotId),
                props = mapOf("header" to header, "width" to 50),
            )
            columnNodeIds.add(colId)
        }
        slots[columnsSlotId] = Slot(id = columnsSlotId, nodeId = datatableNodeId, name = "columns", children = columnNodeIds)
        nodes[datatableNodeId] = Node(
            id = datatableNodeId,
            type = "datatable",
            slots = listOf(columnsSlotId),
            props = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "jsonata"),
                "itemAlias" to "item",
                "borderStyle" to "all",
                "headerEnabled" to true,
            ),
        )
        return documentWithChildren(nodes, listOf(datatableNodeId), slots)
    }

    private fun headerFooterDocument(bodyParagraphs: Int): TemplateDocument {
        val rootSlotId = "slot-root"
        val headerSlotId = "slot-header"
        val footerSlotId = "slot-footer"
        val bodyNodes = (1..bodyParagraphs).associate { i -> "body-$i" to textNode("body-$i", "Body paragraph $i.") }
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "header" to Node(
                    id = "header",
                    type = "pageheader",
                    slots = listOf(headerSlotId),
                    props = mapOf("height" to "30pt"),
                ),
                "header-text" to textNode("header-text", "HEADER CONTENT"),
                "footer" to Node(
                    id = "footer",
                    type = "pagefooter",
                    slots = listOf(footerSlotId),
                    props = mapOf("height" to "30pt"),
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
}

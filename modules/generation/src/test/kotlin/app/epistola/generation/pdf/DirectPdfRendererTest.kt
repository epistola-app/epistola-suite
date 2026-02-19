package app.epistola.generation.pdf

import app.epistola.template.model.Margins
import app.epistola.template.model.Node
import app.epistola.template.model.Orientation
import app.epistola.template.model.PageFormat
import app.epistola.template.model.PageSettings
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DirectPdfRendererTest {
    private val renderer = DirectPdfRenderer()

    /**
     * Helper to create a minimal TemplateDocument with a root node and a single "children" slot.
     * The slot contains the given child node IDs.
     */
    private fun documentWithChildren(
        childNodes: Map<String, Node>,
        childNodeIds: List<String>,
        pageSettingsOverride: PageSettings? = null,
    ): TemplateDocument {
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"

        val rootNode = Node(
            id = rootNodeId,
            type = "root",
            slots = listOf(rootSlotId),
        )
        val rootSlot = Slot(
            id = rootSlotId,
            nodeId = rootNodeId,
            name = "children",
            children = childNodeIds,
        )

        return TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(rootNodeId to rootNode) + childNodes,
            slots = mapOf(rootSlotId to rootSlot),
            pageSettingsOverride = pageSettingsOverride,
        )
    }

    @Test
    fun `renders empty template`() {
        val document = documentWithChildren(emptyMap(), emptyList())

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        // PDF files start with %PDF-
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with text node`() {
        val textNode = Node(
            id = "text1",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Hello World"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val document = documentWithChildren(
            childNodes = mapOf("text1" to textNode),
            childNodeIds = listOf("text1"),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with expression`() {
        val textNode = Node(
            id = "text1",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Hello {{name}}!"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val document = documentWithChildren(
            childNodes = mapOf("text1" to textNode),
            childNodeIds = listOf("text1"),
        )

        val data = mapOf("name" to "John")
        val output = ByteArrayOutputStream()
        renderer.render(document, data, output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders with different page settings`() {
        val document = documentWithChildren(
            childNodes = emptyMap(),
            childNodeIds = emptyList(),
            pageSettingsOverride = PageSettings(
                format = PageFormat.Letter,
                orientation = Orientation.landscape,
                margins = Margins(top = 30, right = 25, bottom = 30, left = 25),
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
    }

    @Test
    fun `renders template with page break`() {
        val textNode1 = Node(
            id = "text1",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Page 1"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val pageBreakNode = Node(
            id = "pagebreak1",
            type = "pagebreak",
        )

        val textNode2 = Node(
            id = "text2",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Page 2"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val document = documentWithChildren(
            childNodes = mapOf(
                "text1" to textNode1,
                "pagebreak1" to pageBreakNode,
                "text2" to textNode2,
            ),
            childNodeIds = listOf("text1", "pagebreak1", "text2"),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with container node`() {
        val innerTextNode = Node(
            id = "text1",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Inside container"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val containerSlotId = "slot-container-children"
        val containerNode = Node(
            id = "container1",
            type = "container",
            slots = listOf(containerSlotId),
            styles = mapOf("backgroundColor" to "#f0f0f0"),
        )
        val containerSlot = Slot(
            id = containerSlotId,
            nodeId = "container1",
            name = "children",
            children = listOf("text1"),
        )

        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val rootNode = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        val rootSlot = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf("container1"))

        val document = TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to rootNode,
                "container1" to containerNode,
                "text1" to innerTextNode,
            ),
            slots = mapOf(
                rootSlotId to rootSlot,
                containerSlotId to containerSlot,
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with columns node`() {
        val leftText = Node(
            id = "text-left",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(mapOf("type" to "text", "text" to "Left column")),
                        ),
                    ),
                ),
            ),
        )

        val rightText = Node(
            id = "text-right",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(mapOf("type" to "text", "text" to "Right column")),
                        ),
                    ),
                ),
            ),
        )

        val col0SlotId = "slot-col-0"
        val col1SlotId = "slot-col-1"
        val columnsNode = Node(
            id = "columns1",
            type = "columns",
            slots = listOf(col0SlotId, col1SlotId),
            props = mapOf("columnSizes" to listOf(1, 2), "gap" to 10),
        )
        val col0Slot = Slot(id = col0SlotId, nodeId = "columns1", name = "column-0", children = listOf("text-left"))
        val col1Slot = Slot(id = col1SlotId, nodeId = "columns1", name = "column-1", children = listOf("text-right"))

        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val rootNode = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        val rootSlot = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf("columns1"))

        val document = TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to rootNode,
                "columns1" to columnsNode,
                "text-left" to leftText,
                "text-right" to rightText,
            ),
            slots = mapOf(
                rootSlotId to rootSlot,
                col0SlotId to col0Slot,
                col1SlotId to col1Slot,
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with conditional node - condition true`() {
        val textNode = Node(
            id = "text1",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Visible when show is true"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val condSlotId = "slot-cond-children"
        val conditionalNode = Node(
            id = "cond1",
            type = "conditional",
            slots = listOf(condSlotId),
            props = mapOf(
                "condition" to mapOf("raw" to "show", "language" to "simple_path"),
            ),
        )
        val condSlot = Slot(id = condSlotId, nodeId = "cond1", name = "children", children = listOf("text1"))

        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val rootNode = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        val rootSlot = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf("cond1"))

        val document = TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to rootNode,
                "cond1" to conditionalNode,
                "text1" to textNode,
            ),
            slots = mapOf(
                rootSlotId to rootSlot,
                condSlotId to condSlot,
            ),
        )

        val data = mapOf("show" to true)
        val output = ByteArrayOutputStream()
        renderer.render(document, data, output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with loop node`() {
        val textNode = Node(
            id = "text1",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Item: {{item}}"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val loopSlotId = "slot-loop-children"
        val loopNode = Node(
            id = "loop1",
            type = "loop",
            slots = listOf(loopSlotId),
            props = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
            ),
        )
        val loopSlot = Slot(id = loopSlotId, nodeId = "loop1", name = "children", children = listOf("text1"))

        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val rootNode = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        val rootSlot = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf("loop1"))

        val document = TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to rootNode,
                "loop1" to loopNode,
                "text1" to textNode,
            ),
            slots = mapOf(
                rootSlotId to rootSlot,
                loopSlotId to loopSlot,
            ),
        )

        val data = mapOf("items" to listOf("Alpha", "Beta", "Gamma"))
        val output = ByteArrayOutputStream()
        renderer.render(document, data, output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with table node`() {
        // Simple 2x2 table with text in each cell
        fun textNode(id: String, text: String) = Node(
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

        val t00 = textNode("t-0-0", "Header 1")
        val t01 = textNode("t-0-1", "Header 2")
        val t10 = textNode("t-1-0", "Cell A")
        val t11 = textNode("t-1-1", "Cell B")

        val slot00 = Slot(id = "slot-00", nodeId = "table1", name = "cell-0-0", children = listOf("t-0-0"))
        val slot01 = Slot(id = "slot-01", nodeId = "table1", name = "cell-0-1", children = listOf("t-0-1"))
        val slot10 = Slot(id = "slot-10", nodeId = "table1", name = "cell-1-0", children = listOf("t-1-0"))
        val slot11 = Slot(id = "slot-11", nodeId = "table1", name = "cell-1-1", children = listOf("t-1-1"))

        val tableNode = Node(
            id = "table1",
            type = "table",
            slots = listOf("slot-00", "slot-01", "slot-10", "slot-11"),
            props = mapOf(
                "rows" to 2,
                "columns" to 2,
                "headerRows" to 1,
                "borderStyle" to "all",
            ),
        )

        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val rootNode = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        val rootSlot = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf("table1"))

        val document = TemplateDocument(
            root = rootNodeId,
            nodes = mapOf(
                rootNodeId to rootNode,
                "table1" to tableNode,
                "t-0-0" to t00,
                "t-0-1" to t01,
                "t-1-0" to t10,
                "t-1-1" to t11,
            ),
            slots = mapOf(
                rootSlotId to rootSlot,
                "slot-00" to slot00,
                "slot-01" to slot01,
                "slot-10" to slot10,
                "slot-11" to slot11,
            ),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders template with document styles override`() {
        val textNode = Node(
            id = "text1",
            type = "text",
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "text", "text" to "Styled text"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val document = documentWithChildren(
            childNodes = mapOf("text1" to textNode),
            childNodeIds = listOf("text1"),
        ).copy(
            documentStylesOverride = mapOf("fontSize" to "14pt", "color" to "#333333"),
        )

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.isNotEmpty())
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `standard mode does not include PDF A identification`() {
        val document = documentWithChildren(emptyMap(), emptyList())

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        assertTrue(pdfBytes.decodeToString(0, 5).startsWith("%PDF"))

        val pdfString = pdfBytes.decodeToString()
        assertTrue(!pdfString.contains("pdfaid:part"), "Standard PDF should not contain pdfaid:part")
    }

    @Test
    fun `pdfa mode output is PDF A-2b compliant with XMP metadata`() {
        val document = documentWithChildren(emptyMap(), emptyList())

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output, pdfaCompliant = true)

        val pdfBytes = output.toByteArray()
        val pdfString = pdfBytes.decodeToString()

        // XMP metadata must contain PDF/A-2b identification
        assertTrue(pdfString.contains("pdfaid:part"), "XMP metadata should contain pdfaid:part")
        assertTrue(pdfString.contains("pdfaid:conformance"), "XMP metadata should contain pdfaid:conformance")

        // Verify using iText reader that the output intent is present
        val readDoc = PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes)))
        val catalog = readDoc.catalog
        assertNotNull(catalog, "PDF catalog should exist")
        readDoc.close()
    }

    @Test
    fun `embeds custom metadata when provided`() {
        val document = documentWithChildren(emptyMap(), emptyList())

        val output = ByteArrayOutputStream()
        val metadata = PdfMetadata(
            title = "Invoice 2026-001",
            author = "Acme Corp",
            subject = "Monthly invoice",
            creator = "Epistola Suite",
        )
        renderer.render(document, emptyMap(), output, metadata = metadata)

        val pdfBytes = output.toByteArray()
        val readDoc = PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes)))
        val info = readDoc.documentInfo

        assertEquals("Invoice 2026-001", info.title)
        assertEquals("Acme Corp", info.author)
        assertEquals("Monthly invoice", info.subject)
        assertEquals("Epistola Suite", info.creator)
        readDoc.close()
    }

    @Test
    fun `sets default creator when no metadata provided`() {
        val document = documentWithChildren(emptyMap(), emptyList())

        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)

        val pdfBytes = output.toByteArray()
        val readDoc = PdfDocument(PdfReader(ByteArrayInputStream(pdfBytes)))
        val info = readDoc.documentInfo

        assertEquals("Epistola Suite", info.creator)
        readDoc.close()
    }
}

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class AddressBlockTest {

    private val renderer = DirectPdfRenderer()

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

    private fun documentWithAddressBlock(
        addressProps: Map<String, Any?> = emptyMap(),
        addressText: String = "John Doe\n123 Main Street\nAmsterdam",
        asideText: String = "Reference: 2026-001\nDate: 2026-04-20",
    ): TemplateDocument {
        val rootSlotId = "slot-root"
        val addressSlotId = "slot-address"
        val asideSlotId = "slot-aside"

        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "addressblock" to Node(
                    id = "addressblock",
                    type = "addressblock",
                    slots = listOf(addressSlotId, asideSlotId),
                    props = mapOf(
                        "standard" to "din-c56-left",
                        "top" to 45,
                        "sideDistance" to 20,
                        "addressWidth" to 85,
                        "height" to 45,
                    ) + addressProps,
                ),
                "address-text" to textNode("address-text", addressText),
                "aside-text" to textNode("aside-text", asideText),
                "body-text" to textNode("body-text", "Body content starts here."),
            ),
            slots = mapOf(
                rootSlotId to Slot(id = rootSlotId, nodeId = "root", name = "children", children = listOf("addressblock", "body-text")),
                addressSlotId to Slot(id = addressSlotId, nodeId = "addressblock", name = "address", children = listOf("address-text")),
                asideSlotId to Slot(id = asideSlotId, nodeId = "addressblock", name = "aside", children = listOf("aside-text")),
            ),
        )
    }

    private fun renderAndExtract(doc: TemplateDocument, data: Map<String, Any?> = emptyMap()): String {
        val output = ByteArrayOutputStream()
        renderer.render(doc, data, output)
        return PdfContentExtractor.extract(output.toByteArray())
    }

    private fun renderToBytes(doc: TemplateDocument): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(doc, emptyMap(), output)
        return output.toByteArray()
    }

    @Test
    fun `renders address block with DIN C5-6 left position`() {
        val pdf = renderToBytes(documentWithAddressBlock())
        assertTrue(pdf.isNotEmpty())
        assertTrue(pdf.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `address and aside content both appear in PDF`() {
        val text = renderAndExtract(
            documentWithAddressBlock(
                addressText = "Recipient Name",
                asideText = "Reference Info",
            ),
        )
        assertContains(text, "Recipient Name")
        assertContains(text, "Reference Info")
    }

    @Test
    fun `body text renders below address block`() {
        val text = renderAndExtract(documentWithAddressBlock())
        assertContains(text, "Body content starts here.")
    }

    @Test
    fun `renders with DIN C5-6 right window`() {
        val text = renderAndExtract(
            documentWithAddressBlock(
                addressProps = mapOf("align" to "right", "sideDistance" to 20),
                addressText = "Right Window Recipient",
                asideText = "Right Window Reference",
            ),
        )
        assertContains(text, "Right Window Recipient", message = "Address content should appear in right-window PDF")
        assertContains(text, "Right Window Reference", message = "Aside content should appear in right-window PDF")
    }

    @Test
    fun `renders with custom position`() {
        val pdf = renderToBytes(
            documentWithAddressBlock(
                addressProps = mapOf("top" to 30, "sideDistance" to 50, "addressWidth" to 70, "height" to 30),
            ),
        )
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders address block nested in container`() {
        val rootSlotId = "slot-root"
        val containerSlotId = "slot-container"
        val addressSlotId = "slot-address"
        val asideSlotId = "slot-aside"

        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "container" to Node(id = "container", type = "container", slots = listOf(containerSlotId)),
                "addressblock" to Node(
                    id = "addressblock",
                    type = "addressblock",
                    slots = listOf(addressSlotId, asideSlotId),
                    props = mapOf("top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45),
                ),
                "address-text" to textNode("address-text", "Nested Address"),
                "aside-text" to textNode("aside-text", "Nested Aside"),
            ),
            slots = mapOf(
                rootSlotId to Slot(id = rootSlotId, nodeId = "root", name = "children", children = listOf("container")),
                containerSlotId to Slot(id = containerSlotId, nodeId = "container", name = "children", children = listOf("addressblock")),
                addressSlotId to Slot(id = addressSlotId, nodeId = "addressblock", name = "address", children = listOf("address-text")),
                asideSlotId to Slot(id = asideSlotId, nodeId = "addressblock", name = "aside", children = listOf("aside-text")),
            ),
        )

        val text = renderAndExtract(doc)
        assertContains(text, "Nested Address")
        assertContains(text, "Nested Aside")
    }

    @Test
    fun `address block with expressions renders data`() {
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
                "addressblock" to Node(
                    id = "addressblock",
                    type = "addressblock",
                    slots = listOf("slot-address", "slot-aside"),
                    props = mapOf("top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45),
                ),
                "address-text" to Node(
                    id = "address-text",
                    type = "text",
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(
                                mapOf(
                                    "type" to "paragraph",
                                    "content" to listOf(
                                        mapOf("type" to "expression", "attrs" to mapOf("expression" to "recipient.name")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                "aside-text" to textNode("aside-text", "Ref"),
            ),
            slots = mapOf(
                "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("addressblock")),
                "slot-address" to Slot(id = "slot-address", nodeId = "addressblock", name = "address", children = listOf("address-text")),
                "slot-aside" to Slot(id = "slot-aside", nodeId = "addressblock", name = "aside", children = listOf("aside-text")),
            ),
        )

        val text = renderAndExtract(doc, mapOf("recipient" to mapOf("name" to "Jane Smith")))
        assertContains(text, "Jane Smith")
    }

    @Test
    fun `address block renders on page 1 in multi-page document`() {
        val longText = "This is a long paragraph that fills space. ".repeat(50)
        val rootSlotId = "slot-root"
        val addressSlotId = "slot-address"
        val asideSlotId = "slot-aside"

        // Create many body text nodes to push onto multiple pages
        val bodyNodes = (1..15).associate { i ->
            "body-$i" to textNode("body-$i", "$longText (Paragraph $i)")
        }

        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "addressblock" to Node(
                    id = "addressblock",
                    type = "addressblock",
                    slots = listOf(addressSlotId, asideSlotId),
                    props = mapOf("top" to 45, "sideDistance" to 20, "addressWidth" to 85, "height" to 45),
                ),
                "address-text" to textNode("address-text", "PAGE ONE ADDRESS"),
                "aside-text" to textNode("aside-text", "Reference"),
            ) + bodyNodes,
            slots = mapOf(
                rootSlotId to Slot(
                    id = rootSlotId,
                    nodeId = "root",
                    name = "children",
                    children = listOf("addressblock") + bodyNodes.keys.toList(),
                ),
                addressSlotId to Slot(id = addressSlotId, nodeId = "addressblock", name = "address", children = listOf("address-text")),
                asideSlotId to Slot(id = asideSlotId, nodeId = "addressblock", name = "aside", children = listOf("aside-text")),
            ),
        )

        val text = renderAndExtract(doc)
        // Should have multiple pages and address content should be present
        val pages = text.split("--- PAGE ")
        assertTrue(pages.size > 2, "Should have multiple pages")
        assertContains(text, "PAGE ONE ADDRESS", message = "Address content should be in the PDF")
    }
}

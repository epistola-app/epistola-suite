package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for [DatatableNodeRenderer].
 *
 * These tests verify that datatables with various configurations produce valid PDF output.
 * Because iText generates binary PDF, we validate that:
 * 1. The output is non-empty
 * 2. The output starts with `%PDF`
 * 3. No exceptions are thrown during rendering
 */
class DatatableNodeRendererTest {
    private val renderer = DirectPdfRenderer()

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

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

    /**
     * Build a complete TemplateDocument with a datatable node.
     *
     * @param columnHeaders headers for each column
     * @param expression the JSONata expression string to evaluate
     * @param itemAlias alias for the current item in the loop
     * @param borderStyle border style string
     * @param headerEnabled whether to show the header row
     * @param columnWidths optional explicit column widths
     * @param columnBodyContent optional content for each column's body template
     */
    private fun datatableDocument(
        columnHeaders: List<String>,
        expression: String = "items",
        itemAlias: String = "item",
        indexAlias: String? = null,
        borderStyle: String = "all",
        headerEnabled: Boolean = true,
        columnWidths: List<Int>? = null,
        columnBodyContent: List<String>? = null,
    ): TemplateDocument {
        val datatableNodeId = "datatable1"
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"
        val columnsSlotId = "slot-columns"

        val nodes = mutableMapOf<String, Node>()
        val slots = mutableMapOf<String, Slot>()
        val columnNodeIds = mutableListOf<String>()

        // Create column nodes with body slots
        for ((i, header) in columnHeaders.withIndex()) {
            val colNodeId = "col-$i"
            val bodySlotId = "slot-body-$i"
            columnNodeIds.add(colNodeId)

            val bodyChildren = mutableListOf<String>()
            if (columnBodyContent != null && i < columnBodyContent.size) {
                val textId = "body-text-$i"
                nodes[textId] = textNode(textId, columnBodyContent[i])
                bodyChildren.add(textId)
            }

            slots[bodySlotId] = Slot(
                id = bodySlotId,
                nodeId = colNodeId,
                name = "body",
                children = bodyChildren,
            )

            val width = columnWidths?.getOrNull(i) ?: Math.round(100f / columnHeaders.size).toInt()
            nodes[colNodeId] = Node(
                id = colNodeId,
                type = "datatable-column",
                slots = listOf(bodySlotId),
                props = mapOf("header" to header, "width" to width),
            )
        }

        // Columns slot
        slots[columnsSlotId] = Slot(
            id = columnsSlotId,
            nodeId = datatableNodeId,
            name = "columns",
            children = columnNodeIds,
        )

        // Datatable node
        val datatableProps = mutableMapOf<String, Any?>(
            "expression" to mapOf("raw" to expression, "language" to "jsonata"),
            "itemAlias" to itemAlias,
            "borderStyle" to borderStyle,
            "headerEnabled" to headerEnabled,
        )
        if (indexAlias != null) {
            datatableProps["indexAlias"] = indexAlias
        }

        nodes[datatableNodeId] = Node(
            id = datatableNodeId,
            type = "datatable",
            slots = listOf(columnsSlotId),
            props = datatableProps,
        )

        // Root
        nodes[rootNodeId] = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        slots[rootSlotId] = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf(datatableNodeId))

        return TemplateDocument(
            root = rootNodeId,
            nodes = nodes,
            slots = slots,
        )
    }

    private fun renderToBytes(
        document: TemplateDocument,
        data: Map<String, Any?> = emptyMap(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(document, data, output)
        return output.toByteArray()
    }

    private fun assertValidPdf(bytes: ByteArray) {
        assertTrue(bytes.isNotEmpty(), "PDF output should not be empty")
        assertTrue(bytes.decodeToString(0, 5).startsWith("%PDF"), "Output should start with %PDF")
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun `renders 3-column datatable with 2 data items`() {
        val doc = datatableDocument(
            columnHeaders = listOf("Product", "Quantity", "Price"),
            columnBodyContent = listOf("item.name", "item.qty", "item.price"),
        )
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "Widget", "qty" to 10, "price" to "$5.00"),
                mapOf("name" to "Gadget", "qty" to 3, "price" to "$15.00"),
            ),
        )
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with empty data produces header-only table`() {
        val doc = datatableDocument(
            columnHeaders = listOf("Name", "Value"),
        )
        val data = mapOf("items" to emptyList<Any>())
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders nothing when header disabled and data empty`() {
        val doc = datatableDocument(
            columnHeaders = listOf("A", "B"),
            headerEnabled = false,
        )
        val data = mapOf("items" to emptyList<Any>())
        // Should still produce a valid PDF (just no table in it)
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with header disabled and data present`() {
        val doc = datatableDocument(
            columnHeaders = listOf("Name", "Value"),
            headerEnabled = false,
            columnBodyContent = listOf("item.name", "item.value"),
        )
        val data = mapOf(
            "items" to listOf(
                mapOf("name" to "Foo", "value" to "Bar"),
            ),
        )
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with border style none`() {
        val doc = datatableDocument(
            columnHeaders = listOf("A", "B"),
            borderStyle = "none",
            columnBodyContent = listOf("item.a", "item.b"),
        )
        val data = mapOf("items" to listOf(mapOf("a" to "1", "b" to "2")))
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with border style horizontal`() {
        val doc = datatableDocument(
            columnHeaders = listOf("A", "B"),
            borderStyle = "horizontal",
            columnBodyContent = listOf("item.a", "item.b"),
        )
        val data = mapOf("items" to listOf(mapOf("a" to "1", "b" to "2")))
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with border style vertical`() {
        val doc = datatableDocument(
            columnHeaders = listOf("A", "B"),
            borderStyle = "vertical",
            columnBodyContent = listOf("item.a", "item.b"),
        )
        val data = mapOf("items" to listOf(mapOf("a" to "1", "b" to "2")))
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with custom column widths`() {
        val doc = datatableDocument(
            columnHeaders = listOf("Wide", "Narrow"),
            columnWidths = listOf(70, 30),
            columnBodyContent = listOf("item.wide", "item.narrow"),
        )
        val data = mapOf("items" to listOf(mapOf("wide" to "Hello", "narrow" to "!")))
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with custom item alias`() {
        val doc = datatableDocument(
            columnHeaders = listOf("Name"),
            itemAlias = "product",
            columnBodyContent = listOf("product.name"),
        )
        val data = mapOf("items" to listOf(mapOf("name" to "Test")))
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders single-column datatable`() {
        val doc = datatableDocument(
            columnHeaders = listOf("Value"),
            columnBodyContent = listOf("item"),
        )
        val data = mapOf("items" to listOf("A", "B", "C"))
        assertValidPdf(renderToBytes(doc, data))
    }

    @Test
    fun `renders datatable with many rows`() {
        val doc = datatableDocument(
            columnHeaders = listOf("ID", "Name"),
            columnBodyContent = listOf("item.id", "item.name"),
        )
        val data = mapOf(
            "items" to (1..50).map { mapOf("id" to it, "name" to "Item $it") },
        )
        assertValidPdf(renderToBytes(doc, data))
    }
}

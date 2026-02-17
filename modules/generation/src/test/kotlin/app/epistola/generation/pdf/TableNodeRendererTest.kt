package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Tests for [TableNodeRenderer], including merge support.
 *
 * These tests verify that tables with various configurations produce valid PDF output.
 * Because iText generates binary PDF, we validate that:
 * 1. The output is non-empty
 * 2. The output starts with `%PDF`
 * 3. No exceptions are thrown during rendering (e.g., iText's table layout breaks if
 *    the cell count doesn't match the column count after accounting for merges)
 */
class TableNodeRendererTest {
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
     * Build a complete TemplateDocument with a table node containing cell slots and text.
     */
    private fun tableDocument(
        rows: Int,
        columns: Int,
        columnWidths: List<Int>? = null,
        borderStyle: String = "all",
        headerRows: Int = 0,
        merges: List<Map<String, Any>> = emptyList(),
    ): TemplateDocument {
        val tableNodeId = "table1"
        val rootNodeId = "root-1"
        val rootSlotId = "slot-root"

        val nodes = mutableMapOf<String, Node>()
        val slots = mutableMapOf<String, Slot>()
        val tableSlotIds = mutableListOf<String>()

        // Create cell slots with text nodes
        for (row in 0 until rows) {
            for (col in 0 until columns) {
                val slotId = "slot-$row-$col"
                val textId = "t-$row-$col"

                tableSlotIds.add(slotId)
                nodes[textId] = textNode(textId, "R${row}C$col")
                slots[slotId] = Slot(
                    id = slotId,
                    nodeId = tableNodeId,
                    name = "cell-$row-$col",
                    children = listOf(textId),
                )
            }
        }

        val tableProps = mutableMapOf<String, Any?>(
            "rows" to rows,
            "columns" to columns,
            "borderStyle" to borderStyle,
            "headerRows" to headerRows,
        )
        if (columnWidths != null) {
            tableProps["columnWidths"] = columnWidths
        }
        if (merges.isNotEmpty()) {
            tableProps["merges"] = merges
        }

        nodes[tableNodeId] = Node(
            id = tableNodeId,
            type = "table",
            slots = tableSlotIds,
            props = tableProps,
        )

        // Root
        nodes[rootNodeId] = Node(id = rootNodeId, type = "root", slots = listOf(rootSlotId))
        slots[rootSlotId] = Slot(id = rootSlotId, nodeId = rootNodeId, name = "children", children = listOf(tableNodeId))

        return TemplateDocument(
            root = rootNodeId,
            nodes = nodes,
            slots = slots,
        )
    }

    private fun renderToBytes(document: TemplateDocument): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(document, emptyMap(), output)
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
    fun `renders basic 2x2 table`() {
        val doc = tableDocument(rows = 2, columns = 2)
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders 1x1 table`() {
        val doc = tableDocument(rows = 1, columns = 1)
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders 3x4 table with custom column widths`() {
        val doc = tableDocument(rows = 3, columns = 4, columnWidths = listOf(10, 30, 30, 30))
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with header rows`() {
        val doc = tableDocument(rows = 3, columns = 2, headerRows = 1)
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with border style none`() {
        val doc = tableDocument(rows = 2, columns = 2, borderStyle = "none")
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with border style horizontal`() {
        val doc = tableDocument(rows = 2, columns = 2, borderStyle = "horizontal")
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with border style vertical`() {
        val doc = tableDocument(rows = 2, columns = 2, borderStyle = "vertical")
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with colspan merge`() {
        // Merge cells (0,0) and (0,1) into a single cell spanning 2 columns
        val doc = tableDocument(
            rows = 2,
            columns = 2,
            merges = listOf(
                mapOf("row" to 0, "col" to 0, "rowSpan" to 1, "colSpan" to 2),
            ),
        )
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with rowspan merge`() {
        // Merge cells (0,0) and (1,0) into a single cell spanning 2 rows
        val doc = tableDocument(
            rows = 2,
            columns = 2,
            merges = listOf(
                mapOf("row" to 0, "col" to 0, "rowSpan" to 2, "colSpan" to 1),
            ),
        )
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with 2x2 merge block`() {
        // 3x3 table with top-left 2x2 merged
        val doc = tableDocument(
            rows = 3,
            columns = 3,
            merges = listOf(
                mapOf("row" to 0, "col" to 0, "rowSpan" to 2, "colSpan" to 2),
            ),
        )
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with multiple merges`() {
        // 3x3 table with two separate merges
        val doc = tableDocument(
            rows = 3,
            columns = 3,
            merges = listOf(
                mapOf("row" to 0, "col" to 0, "rowSpan" to 1, "colSpan" to 2), // top row colspan
                mapOf("row" to 1, "col" to 2, "rowSpan" to 2, "colSpan" to 1), // right column rowspan
            ),
        )
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with merges and header rows`() {
        // Header row that is merged across all columns
        val doc = tableDocument(
            rows = 3,
            columns = 3,
            headerRows = 1,
            merges = listOf(
                mapOf("row" to 0, "col" to 0, "rowSpan" to 1, "colSpan" to 3),
            ),
        )
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with no merges prop gracefully`() {
        // The existing table test — no merges prop at all
        val doc = tableDocument(rows = 2, columns = 2)
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `renders table with empty merges list`() {
        val doc = tableDocument(rows = 2, columns = 2, merges = emptyList())
        assertValidPdf(renderToBytes(doc))
    }

    @Test
    fun `ignores invalid merge entries gracefully`() {
        // Merges with missing fields should be silently ignored
        val doc = tableDocument(
            rows = 2,
            columns = 2,
            merges = listOf(
                mapOf("row" to 0), // incomplete
                mapOf("row" to 0, "col" to 0, "rowSpan" to 0, "colSpan" to 1), // rowSpan < 1
            ),
        )
        assertValidPdf(renderToBytes(doc))
    }
}

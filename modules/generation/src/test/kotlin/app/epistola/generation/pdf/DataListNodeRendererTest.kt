package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

class DataListNodeRendererTest {

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

    private fun documentWithDataList(
        listProps: Map<String, Any?>,
        data: Map<String, Any?> = emptyMap(),
    ): Pair<TemplateDocument, Map<String, Any?>> {
        val rootSlotId = "slot-root"
        val itemSlotId = "slot-item"

        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf(rootSlotId)),
                "datalist" to Node(id = "datalist", type = "datalist", slots = listOf(itemSlotId), props = listProps),
                "item-text" to textNode("item-text", "{{item}}"),
            ),
            slots = mapOf(
                rootSlotId to Slot(id = rootSlotId, nodeId = "root", name = "children", children = listOf("datalist")),
                itemSlotId to Slot(id = itemSlotId, nodeId = "datalist", name = "item-template", children = listOf("item-text")),
            ),
        )
        return doc to data
    }

    private fun renderToPdf(doc: TemplateDocument, data: Map<String, Any?>): ByteArray {
        val output = ByteArrayOutputStream()
        renderer.render(doc, data, output)
        return output.toByteArray()
    }

    @Test
    fun `renders bullet list from data`() {
        val (doc, data) = documentWithDataList(
            listProps = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
                "listType" to "bullet",
            ),
            data = mapOf("items" to listOf("Alpha", "Beta", "Gamma")),
        )
        val pdf = renderToPdf(doc, data)
        assertTrue(pdf.isNotEmpty())
        assertTrue(pdf.decodeToString(0, 5).startsWith("%PDF"))
    }

    @Test
    fun `renders numbered list from data`() {
        val (doc, data) = documentWithDataList(
            listProps = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
                "listType" to "decimal",
            ),
            data = mapOf("items" to listOf("First", "Second", "Third")),
        )
        val pdf = renderToPdf(doc, data)
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders lower-alpha list`() {
        val (doc, data) = documentWithDataList(
            listProps = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
                "listType" to "lower-alpha",
            ),
            data = mapOf("items" to listOf("X", "Y", "Z")),
        )
        val pdf = renderToPdf(doc, data)
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders empty list when data is empty`() {
        val (doc, data) = documentWithDataList(
            listProps = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
                "listType" to "bullet",
            ),
            data = mapOf("items" to emptyList<String>()),
        )
        val pdf = renderToPdf(doc, data)
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders list with no marker`() {
        val (doc, data) = documentWithDataList(
            listProps = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
                "listType" to "none",
            ),
            data = mapOf("items" to listOf("One", "Two")),
        )
        val pdf = renderToPdf(doc, data)
        assertTrue(pdf.isNotEmpty())
    }

    @Test
    fun `renders list with index alias`() {
        val (doc, data) = documentWithDataList(
            listProps = mapOf(
                "expression" to mapOf("raw" to "items", "language" to "simple_path"),
                "itemAlias" to "item",
                "indexAlias" to "idx",
                "listType" to "decimal",
            ),
            data = mapOf("items" to listOf("A", "B")),
        )
        val pdf = renderToPdf(doc, data)
        assertTrue(pdf.isNotEmpty())
    }
}

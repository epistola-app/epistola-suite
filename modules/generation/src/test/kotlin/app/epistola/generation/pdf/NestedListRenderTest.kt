package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression for nested lists inside a Text component ("een opsomming in een opsomming"):
 * a `bullet_list`/`ordered_list` nested inside a `listItem` must render its inner items, not
 * silently drop them. The converter previously only walked `paragraph` children of a list item.
 */
class NestedListRenderTest {

    private val renderer = DirectPdfRenderer()

    private fun para(text: String) = mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text)))

    /** A list_item holding a paragraph plus an optional nested list. */
    private fun listItem(text: String, nested: Map<String, Any>? = null) = mapOf(
        "type" to "listItem",
        "content" to listOfNotNull(para(text), nested),
    )

    private fun bulletList(vararg items: Map<String, Any>) = mapOf("type" to "bulletList", "attrs" to mapOf("listStyle" to "disc"), "content" to items.toList())

    private fun render(vararg docContent: Map<String, Any>): String {
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("s")),
                "text" to Node(
                    id = "text",
                    type = "text",
                    props = mapOf("content" to mapOf("type" to "doc", "content" to docContent.toList())),
                ),
            ),
            slots = mapOf("s" to Slot(id = "s", nodeId = "root", name = "children", children = listOf("text"))),
        )
        val out = ByteArrayOutputStream()
        renderer.render(doc, emptyMap(), out)
        return PdfContentExtractor.extract(out.toByteArray())
    }

    @Test
    fun `nested bullet list renders its inner items`() {
        // Outer list: "Fruit" (with nested Apple/Pear) and "Vegetables".
        val text = render(
            bulletList(
                listItem("Fruit", bulletList(listItem("Apple"), listItem("Pear"))),
                listItem("Vegetables"),
            ),
        )
        for (expected in listOf("Fruit", "Apple", "Pear", "Vegetables")) {
            assertTrue(text.contains(expected), "expected nested-list item '$expected' in PDF, got:\n$text")
        }
    }

    @Test
    fun `nested ordered list inside a bullet item renders its inner items`() {
        val orderedSteps = mapOf(
            "type" to "orderedList",
            "attrs" to mapOf("listType" to "decimal"),
            "content" to listOf(listItem("First"), listItem("Second")),
        )
        val text = render(bulletList(listItem("Steps", orderedSteps), listItem("Done")))
        for (expected in listOf("Steps", "First", "Second", "Done")) {
            assertTrue(text.contains(expected), "expected '$expected' in PDF, got:\n$text")
        }
    }
}

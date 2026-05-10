package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.generation.expression.JsonataEvaluator
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import com.itextpdf.layout.element.Div
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RichTextVariableRendererTest {
    private val evaluator = CompositeExpressionEvaluator(jsonataEvaluator = JsonataEvaluator())
    private val fontCache = FontCache(pdfaCompliant = false)
    private val converter = TipTapConverter(evaluator)
    private val renderer = RichTextVariableRenderer()

    private fun docWith(node: Node): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "n-root",
        nodes = mapOf(
            "n-root" to Node(id = "n-root", type = "root", slots = listOf("s-root")),
            node.id to node,
        ),
        slots = mapOf(
            "s-root" to Slot(id = "s-root", nodeId = "n-root", name = "children", children = listOf(node.id)),
        ),
        themeRef = ThemeRef.Inherit,
    )

    private fun contextWith(data: Map<String, Any?>, doc: TemplateDocument) = RenderContext(
        data = data,
        expressionEvaluator = evaluator,
        tipTapConverter = converter,
        fontCache = fontCache,
        document = doc,
    )

    @Test
    fun `renders empty Div when binding is missing`() {
        val node = Node(id = "n-rt", type = "richTextVariable", slots = emptyList(), props = mapOf("binding" to ""))
        val doc = docWith(node)
        val result = renderer.render(node, doc, contextWith(emptyMap(), doc), NodeRendererRegistry(emptyMap()))
        assertEquals(1, result.size)
        val div = assertIs<Div>(result[0])
        assertTrue(div.children.isEmpty(), "expected empty Div when binding is empty")
    }

    @Test
    fun `renders block elements when binding resolves to a doc`() {
        val node = Node(
            id = "n-rt",
            type = "richTextVariable",
            slots = emptyList(),
            props = mapOf("binding" to "bio"),
        )
        val doc = docWith(node)
        val data: Map<String, Any?> = mapOf(
            "bio" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf(
                        "type" to "paragraph",
                        "content" to listOf(
                            mapOf("type" to "text", "text" to "Hello "),
                            mapOf("type" to "text", "text" to "world", "marks" to listOf(mapOf("type" to "strong"))),
                        ),
                    ),
                    mapOf(
                        "type" to "bullet_list",
                        "content" to listOf(
                            mapOf(
                                "type" to "list_item",
                                "content" to listOf(
                                    mapOf(
                                        "type" to "paragraph",
                                        "content" to listOf(mapOf("type" to "text", "text" to "item")),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val result = renderer.render(node, doc, contextWith(data, doc), NodeRendererRegistry(emptyMap()))
        assertEquals(1, result.size)
        val div = assertIs<Div>(result[0])
        // Expect a paragraph and a list (2 block children) under the Div.
        assertEquals(2, div.children.size, "expected paragraph + list inside Div")
    }

    @Test
    fun `renders empty Div when binding resolves to a non-doc value`() {
        val node = Node(
            id = "n-rt",
            type = "richTextVariable",
            slots = emptyList(),
            props = mapOf("binding" to "name"),
        )
        val doc = docWith(node)
        val data: Map<String, Any?> = mapOf("name" to "plain string, not a doc")
        val result = renderer.render(node, doc, contextWith(data, doc), NodeRendererRegistry(emptyMap()))
        assertEquals(1, result.size)
        val div = assertIs<Div>(result[0])
        assertTrue(div.children.isEmpty(), "expected empty Div for non-doc binding values")
    }
}

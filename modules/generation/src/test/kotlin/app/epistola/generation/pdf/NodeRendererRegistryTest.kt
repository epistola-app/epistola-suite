package app.epistola.generation.pdf

import app.epistola.generation.TipTapConverter
import app.epistola.generation.expression.CompositeExpressionEvaluator
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class NodeRendererRegistryTest {

    private val registry = DirectPdfRenderer.createDefaultRegistry()
    private val evaluator = CompositeExpressionEvaluator()
    private val fontCache = FontCache(pdfaCompliant = false)
    private val tipTapConverter = TipTapConverter(evaluator)

    private fun contextFor(doc: TemplateDocument) = RenderContext(
        data = emptyMap(),
        expressionEvaluator = evaluator,
        tipTapConverter = tipTapConverter,
        fontCache = fontCache,
        document = doc,
    )

    @Test
    fun `renderNode throws on unknown node type`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf(
                "n-root" to Node(id = "n-root", type = "root", slots = listOf("s-main")),
                "n-bad" to Node(id = "n-bad", type = "nonexistent-type", slots = emptyList()),
            ),
            slots = mapOf(
                "s-main" to Slot(id = "s-main", nodeId = "n-root", name = "children", children = listOf("n-bad")),
            ),
            themeRef = ThemeRef.Inherit,
        )

        val error = assertFailsWith<IllegalStateException> {
            registry.renderNode("n-bad", doc, contextFor(doc))
        }
        assertContains(error.message!!, "Unknown node type 'nonexistent-type'")
        assertContains(error.message!!, "n-bad")
    }

    @Test
    fun `renderNode throws on missing node ID`() {
        val doc = TemplateDocument(
            modelVersion = 1,
            root = "n-root",
            nodes = mapOf(
                "n-root" to Node(id = "n-root", type = "root", slots = emptyList()),
            ),
            slots = emptyMap(),
            themeRef = ThemeRef.Inherit,
        )

        val error = assertFailsWith<IllegalStateException> {
            registry.renderNode("n-does-not-exist", doc, contextFor(doc))
        }
        assertContains(error.message!!, "n-does-not-exist")
        assertContains(error.message!!, "not found")
    }
}

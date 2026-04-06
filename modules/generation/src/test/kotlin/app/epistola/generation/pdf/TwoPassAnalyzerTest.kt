package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwoPassAnalyzerTest {

    private fun minimalDocument(vararg extraNodes: Node): TemplateDocument {
        val rootSlot = Slot(id = "s1", nodeId = "root", name = "children", children = extraNodes.map { it.id })
        val allNodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("s1"))) +
            extraNodes.associateBy { it.id }
        return TemplateDocument(root = "root", nodes = allNodes, slots = mapOf("s1" to rootSlot))
    }

    private fun textNodeWithExpression(id: String, expression: String): Node = Node(
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
                                "type" to "expression",
                                "attrs" to mapOf("expression" to expression),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    // --- requiresTwoPassRendering ---

    @Test
    fun `no expressions returns false`() {
        val doc = minimalDocument()
        assertFalse(TwoPassAnalyzer.requiresTwoPassRendering(doc))
    }

    @Test
    fun `sys page number only returns false`() {
        val doc = minimalDocument(textNodeWithExpression("t1", "sys.pages.current"))
        assertFalse(TwoPassAnalyzer.requiresTwoPassRendering(doc))
    }

    @Test
    fun `sys page total in inline expression returns true`() {
        val doc = minimalDocument(textNodeWithExpression("t1", "sys.pages.total"))
        assertTrue(TwoPassAnalyzer.requiresTwoPassRendering(doc))
    }

    @Test
    fun `sys page total in larger expression returns true`() {
        val doc = minimalDocument(
            textNodeWithExpression("t1", "sys.pages.current & '/' & sys.pages.total"),
        )
        assertTrue(TwoPassAnalyzer.requiresTwoPassRendering(doc))
    }

    @Test
    fun `sys page total in node-level expression prop returns true`() {
        val doc = minimalDocument(
            Node(
                id = "qr1",
                type = "qrcode",
                props = mapOf("value" to mapOf("raw" to "sys.pages.total", "language" to "jsonata")),
            ),
        )
        assertTrue(TwoPassAnalyzer.requiresTwoPassRendering(doc))
    }

    @Test
    fun `sys page total in header returns true`() {
        val doc = minimalDocument(
            textNodeWithExpression("h1", "sys.pages.total").copy(type = "pageheader"),
        )
        assertTrue(TwoPassAnalyzer.requiresTwoPassRendering(doc))
    }

    // --- validate ---

    @Test
    fun `validate passes for text node with sys page total`() {
        val doc = minimalDocument(textNodeWithExpression("t1", "sys.pages.total"))
        TwoPassAnalyzer.validate(doc) // should not throw
    }

    @Test
    fun `validate passes for header with sys page total`() {
        val doc = minimalDocument(
            textNodeWithExpression("h1", "sys.pages.total").copy(type = "pageheader"),
        )
        TwoPassAnalyzer.validate(doc) // should not throw
    }

    @Test
    fun `validate passes for footer with sys page total`() {
        val doc = minimalDocument(
            textNodeWithExpression("f1", "sys.pages.total").copy(type = "pagefooter"),
        )
        TwoPassAnalyzer.validate(doc) // should not throw
    }

    @Test
    fun `validate passes for static table with sys page total`() {
        val doc = minimalDocument(
            textNodeWithExpression("t1", "sys.pages.total").copy(type = "table"),
        )
        TwoPassAnalyzer.validate(doc) // should not throw — static tables have fixed rows
    }

    @Test
    fun `validate throws for datatable with sys page total`() {
        val doc = minimalDocument(
            Node(
                id = "dt1",
                type = "datatable",
                props = mapOf(
                    "expression" to mapOf("raw" to "sys.pages.total", "language" to "jsonata"),
                ),
            ),
        )
        val error = assertFailsWith<IllegalArgumentException> { TwoPassAnalyzer.validate(doc) }
        assertTrue(error.message!!.contains("dt1"))
        assertTrue(error.message!!.contains("datatable"))
        assertTrue(error.message!!.contains("sys.pages.total"))
    }

    @Test
    fun `validate throws for conditional with sys page total in condition prop`() {
        val doc = minimalDocument(
            Node(
                id = "cond1",
                type = "conditional",
                props = mapOf(
                    "condition" to mapOf("raw" to "sys.pages.total > 1", "language" to "jsonata"),
                ),
            ),
        )
        val error = assertFailsWith<IllegalArgumentException> { TwoPassAnalyzer.validate(doc) }
        assertTrue(error.message!!.contains("cond1"))
        assertTrue(error.message!!.contains("sys.pages.total"))
        assertTrue(error.message!!.contains("conditional"))
    }

    @Test
    fun `validate throws for loop with sys page total in expression prop`() {
        val doc = minimalDocument(
            Node(
                id = "loop1",
                type = "loop",
                props = mapOf(
                    "expression" to mapOf("raw" to "sys.pages.total", "language" to "jsonata"),
                ),
            ),
        )
        val error = assertFailsWith<IllegalArgumentException> { TwoPassAnalyzer.validate(doc) }
        assertTrue(error.message!!.contains("loop1"))
        assertTrue(error.message!!.contains("loop"))
    }

    @Test
    fun `validate throws for conditional with sys page total in inline expression`() {
        val conditionalNode = Node(
            id = "cond2",
            type = "conditional",
            props = mapOf(
                "condition" to mapOf("raw" to "true", "language" to "jsonata"),
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf(
                                    "type" to "expression",
                                    "attrs" to mapOf("expression" to "sys.pages.total"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val doc = minimalDocument(conditionalNode)
        val error = assertFailsWith<IllegalArgumentException> { TwoPassAnalyzer.validate(doc) }
        assertTrue(error.message!!.contains("cond2"))
    }

    @Test
    fun `validate error message includes node id and pattern`() {
        val doc = minimalDocument(
            Node(
                id = "my-conditional",
                type = "conditional",
                props = mapOf(
                    "condition" to mapOf("raw" to "sys.pages.total > 5", "language" to "jsonata"),
                ),
            ),
        )
        val error = assertFailsWith<IllegalArgumentException> { TwoPassAnalyzer.validate(doc) }
        assertEquals(
            "Expression 'sys.pages.total > 5' in conditional node 'my-conditional' references 'sys.pages.total', " +
                "which is not allowed in conditional nodes because it could destabilize page count between render passes. " +
                "Use 'sys.pages.total' only in text, headers, or footers.",
            error.message,
        )
    }

    // --- page-scoped validation ---

    private fun documentWithPageFooterChild(childNode: Node): TemplateDocument {
        val footerSlot = Slot(id = "s-footer", nodeId = "footer", name = "children", children = listOf(childNode.id))
        val footerNode = Node(id = "footer", type = "pagefooter", slots = listOf("s-footer"))
        val rootSlot = Slot(id = "s1", nodeId = "root", name = "children", children = emptyList())
        return TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("s1")),
                "footer" to footerNode,
                childNode.id to childNode,
            ),
            slots = mapOf("s1" to rootSlot, "s-footer" to footerSlot),
        )
    }

    @Test
    fun `validate passes for sys pages current inside pagefooter`() {
        val doc = documentWithPageFooterChild(
            textNodeWithExpression("ft1", "sys.pages.current"),
        )
        TwoPassAnalyzer.validate(doc) // should not throw
    }

    @Test
    fun `validate passes for sys pages current inside pageheader`() {
        val headerSlot = Slot(id = "s-header", nodeId = "header", name = "children", children = listOf("ht1"))
        val headerNode = Node(id = "header", type = "pageheader", slots = listOf("s-header"))
        val rootSlot = Slot(id = "s1", nodeId = "root", name = "children", children = emptyList())
        val doc = TemplateDocument(
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("s1")),
                "header" to headerNode,
                "ht1" to textNodeWithExpression("ht1", "sys.pages.current"),
            ),
            slots = mapOf("s1" to rootSlot, "s-header" to headerSlot),
        )
        TwoPassAnalyzer.validate(doc) // should not throw
    }

    @Test
    fun `validate throws for sys pages current in body text node`() {
        val doc = minimalDocument(textNodeWithExpression("t1", "sys.pages.current"))
        val error = assertFailsWith<IllegalArgumentException> { TwoPassAnalyzer.validate(doc) }
        assertTrue(error.message!!.contains("sys.pages.current"))
        assertTrue(error.message!!.contains("only available in page headers and footers"))
    }

    @Test
    fun `validate throws for sys pages current in body expression`() {
        val doc = minimalDocument(
            textNodeWithExpression("t1", "sys.pages.current & ' / ' & sys.pages.total"),
        )
        val error = assertFailsWith<IllegalArgumentException> { TwoPassAnalyzer.validate(doc) }
        assertTrue(error.message!!.contains("sys.pages.current"))
    }
}

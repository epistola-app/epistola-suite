// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.generation.expression.UnsupportedExpressionLanguageException
import app.epistola.generation.expression.UnsupportedExpressionLanguages
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JavaScript expressions are no longer evaluated. A stored template that still contains one must
 * fail to render with a clear error — never hang the worker, and never be silently evaluated as
 * JSONata (which could produce a document with different content than the author intended).
 */
class JavaScriptExpressionRejectionTest {
    private val renderer = DirectPdfRenderer()

    /** An expression that would never terminate if it were evaluated as JavaScript. */
    private val hostileExpression = mapOf("raw" to "while(true){}", "language" to "javascript")

    private fun documentWith(node: Node): TemplateDocument {
        val rootSlot = Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf(node.id))
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf(rootSlot.id)), node.id to node),
            slots = mapOf(rootSlot.id to rootSlot),
        )
    }

    private fun assertRejected(document: TemplateDocument) {
        val error = assertFailsWith<UnsupportedExpressionLanguageException> {
            renderer.render(document, mapOf("items" to listOf(1, 2)), ByteArrayOutputStream())
        }
        assertEquals(UnsupportedExpressionLanguages.JAVASCRIPT_MESSAGE, error.message)
    }

    @Test
    fun `conditional with a javascript condition fails the render`() {
        assertRejected(documentWith(Node(id = "c", type = "conditional", slots = emptyList(), props = mapOf("condition" to hostileExpression))))
    }

    @Test
    fun `loop with a javascript expression fails the render`() {
        assertRejected(documentWith(Node(id = "l", type = "loop", slots = emptyList(), props = mapOf("expression" to hostileExpression))))
    }

    @Test
    fun `qr code with a javascript value fails the render`() {
        assertRejected(documentWith(Node(id = "q", type = "qrcode", slots = emptyList(), props = mapOf("value" to hostileExpression))))
    }

    @Test
    fun `rich text variable with a javascript binding fails the render`() {
        assertRejected(
            documentWith(
                Node(
                    id = "r",
                    type = "richTextVariable",
                    slots = emptyList(),
                    props = mapOf("binding" to "while(true){}", "language" to "javascript"),
                ),
            ),
        )
    }

    @Test
    fun `inline javascript expression in text fails the render`() {
        val text = Node(
            id = "t",
            type = "text",
            slots = emptyList(),
            props = mapOf(
                "content" to mapOf(
                    "type" to "doc",
                    "content" to listOf(
                        mapOf(
                            "type" to "paragraph",
                            "content" to listOf(
                                mapOf("type" to "expression", "attrs" to mapOf("expression" to "while(true){}", "language" to "javascript")),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertRejected(documentWith(text))
    }

    @Test
    fun `an unknown language still falls back to the default language`() {
        val node = Node(
            id = "c",
            type = "conditional",
            slots = emptyList(),
            props = mapOf("condition" to mapOf("raw" to "true", "language" to "cobol")),
        )
        val output = ByteArrayOutputStream()
        renderer.render(documentWith(node), emptyMap(), output)
        assertTrue(output.toByteArray().isNotEmpty())
    }
}

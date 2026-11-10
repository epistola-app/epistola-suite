// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * An expression refused for what it would cost fails the whole render with a message naming it, from
 * wherever in the template it sits: the editor preview shows that message, and a document's
 * generation fails with it. Other expression errors keep rendering as empty.
 */
class ExpressionLimitRenderTest {
    private val renderer = DirectPdfRenderer()
    private val tooWide = "\$pad('x', 500)"

    private fun documentWith(node: Node): TemplateDocument {
        val rootSlot = Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf(node.id))
        return TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf(rootSlot.id)), node.id to node),
            slots = mapOf(rootSlot.id to rootSlot),
        )
    }

    private fun assertRefused(document: TemplateDocument) {
        val error = assertFailsWith<IllegalArgumentException> {
            renderer.render(document, mapOf("items" to listOf(1, 2)), ByteArrayOutputStream())
        }
        assertContains(error.message!!, tooWide)
    }

    private fun text(expression: String) = Node(
        id = "t",
        type = "text",
        slots = emptyList(),
        props = mapOf(
            "content" to mapOf(
                "type" to "doc",
                "content" to listOf(
                    mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "expression", "attrs" to mapOf("expression" to expression)))),
                ),
            ),
        ),
    )

    @Test
    fun `an inline expression past a limit fails the render`() {
        assertRefused(documentWith(text(tooWide)))
    }

    @Test
    fun `a condition past a limit fails the render`() {
        assertRefused(documentWith(Node(id = "c", type = "conditional", slots = emptyList(), props = mapOf("condition" to mapOf("raw" to tooWide)))))
    }

    @Test
    fun `a loop expression past a limit fails the render`() {
        assertRefused(documentWith(Node(id = "l", type = "loop", slots = emptyList(), props = mapOf("expression" to mapOf("raw" to tooWide)))))
    }

    @Test
    fun `an expression that merely fails still renders as empty`() {
        renderer.render(documentWith(text("\$unknownFunction(1)")), emptyMap(), ByteArrayOutputStream())
    }
}

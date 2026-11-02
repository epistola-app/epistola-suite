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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A PDF/A render with a character its font has no glyph for fails. iText says only
 * "The '' glyph isn't defined in embedded font": no code point, no font, no place, and an
 * invisible character shows as nothing at all. The failure must say which character, which
 * font lacks it and where it is.
 */
class UnrenderableCharacterTest {

    private val renderer = DirectPdfRenderer()

    private fun document(vararg inline: Map<String, Any>) = TemplateDocument(
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("slot-root")),
            "greeting" to Node(
                id = "greeting",
                type = "text",
                props = mapOf(
                    "content" to mapOf(
                        "type" to "doc",
                        "content" to listOf(mapOf("type" to "paragraph", "content" to inline.toList())),
                    ),
                ),
            ),
        ),
        slots = mapOf("slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("greeting"))),
    )

    private fun text(value: String) = mapOf("type" to "text", "text" to value)

    private fun expression(path: String) = mapOf("type" to "expression", "attrs" to mapOf("expression" to path))

    private fun renderPdfA(document: TemplateDocument, data: Map<String, Any?> = emptyMap()) = renderer.render(document, data, ByteArrayOutputStream(), pdfaCompliant = true)

    @Test
    fun `a character in the template names its code point, the font and the block with an excerpt`() {
        val failure = assertFailsWith<UnrenderableCharacterException> {
            renderPdfA(document(text("Dear customer 😀 thank you")))
        }

        assertEquals(0x1F600, failure.codePoint)
        assertEquals(listOf("LiberationSans"), failure.fontsLacking)
        assertContains(failure.message!!, "U+1F600 (GRINNING FACE)")
        assertContains(failure.message!!, "the font LiberationSans has no glyph for")
        assertContains(failure.message!!, "text block greeting: \"Dear customer [U+1F600] thank you\"")
    }

    @Test
    fun `an invisible character is named, not shown as nothing`() {
        val failure = assertFailsWith<UnrenderableCharacterException> {
            renderPdfA(document(text("Itemone")))
        }

        assertContains(failure.message!!, "U+E000 (PRIVATE USE AREA E000)")
        assertContains(failure.message!!, "\"Item[U+E000]one\"")
    }

    @Test
    fun `a character from the data is named by its path and the data is not quoted`() {
        val failure = assertFailsWith<UnrenderableCharacterException> {
            renderPdfA(document(text("Note: "), expression("customer.note")), mapOf("customer" to mapOf("note" to "private remark 😀")))
        }

        assertContains(failure.message!!, "the data at customer.note")
        assertFalse(failure.message!!.contains("private remark"), "data may be personal and must not be quoted: ${failure.message}")
    }

    @Test
    fun `any other failure is left as it is`() {
        val other = IllegalStateException("something else")
        assertNull(UnrenderableCharacterException.from(other, document(text("x")), emptyMap(), emptyList()))
    }
}

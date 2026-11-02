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
import kotlin.test.assertFalse

/**
 * Text pasted from Word carries characters no font draws: its manual line break (U+000B), page
 * break (U+000C), non-breaking hyphen (U+001E), optional hyphen (U+001F) and Symbol or Wingdings
 * bullets (U+F0B7 …). A PDF/A render failed on every one of them, in every system font. They must
 * render as what they mean, whether they come from the template or from the data.
 */
class WordPasteRenderTest {

    private val renderer = DirectPdfRenderer()

    private val wordText = "Postbus 12\u000B1234 AB Utrecht\u000CTel. 06\u001E12345678, onder\u001Fwerp\tfirst item"

    private fun paragraph(vararg inline: Map<String, Any>) = mapOf("type" to "paragraph", "content" to inline.toList())

    private fun textNode(id: String, vararg inline: Map<String, Any>) = Node(
        id = id,
        type = "text",
        props = mapOf("content" to mapOf("type" to "doc", "content" to listOf(paragraph(*inline)))),
    )

    private fun text(value: String) = mapOf("type" to "text", "text" to value)

    private fun document(vararg nodes: Node) = TemplateDocument(
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("slot-root"))) + nodes.associateBy { it.id },
        slots = mapOf("slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = nodes.map { it.id })),
    )

    private fun render(
        document: TemplateDocument,
        data: Map<String, Any?> = emptyMap(),
        pdfa: Boolean = true,
        defaults: RenderingDefaults = RenderingDefaults.CURRENT,
    ): String = ByteArrayOutputStream()
        .also { renderer.render(document, data, it, pdfaCompliant = pdfa, renderingDefaults = defaults) }
        .toByteArray()
        .let(PdfContentExtractor::extract)
        // The extractor leaves a space where a line ends.
        .replace(Regex(" +\n"), "\n")

    private fun assertWordTextRendered(extracted: String) {
        assertContains(extracted, "Postbus 12\n1234 AB Utrecht\nTel. 06-12345678, onderwerp•")
        assertContains(extracted, "first item")
    }

    @Test
    fun `Word characters in template text render as PDF A, also with the defaults of a 1_x release`() {
        assertWordTextRendered(render(document(textNode("letter", text(wordText))), defaults = RenderingDefaults.V6))
    }

    @Test
    fun `Word characters in the data render as PDF A`() {
        val expression = mapOf("type" to "expression", "attrs" to mapOf("expression" to "address"))
        assertWordTextRendered(render(document(textNode("letter", expression)), mapOf("address" to wordText)))
    }

    @Test
    fun `a Word line break in a datatable column header renders as PDF A`() {
        val column = Node(id = "col", type = "datatable-column", slots = listOf("slot-body"), props = mapOf("header" to "Amount\u000Bin euro", "width" to 100))
        val table = Node(
            id = "table",
            type = "datatable",
            slots = listOf("slot-columns"),
            props = mapOf("expression" to mapOf("raw" to "items", "language" to "jsonata"), "itemAlias" to "item", "headerEnabled" to true),
        )
        val document = TemplateDocument(
            root = "root",
            nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("slot-root")), "table" to table, "col" to column),
            slots = mapOf(
                "slot-root" to Slot(id = "slot-root", nodeId = "root", name = "children", children = listOf("table")),
                "slot-columns" to Slot(id = "slot-columns", nodeId = "table", name = "columns", children = listOf("col")),
                "slot-body" to Slot(id = "slot-body", nodeId = "col", name = "body", children = emptyList()),
            ),
        )

        assertContains(render(document, mapOf("items" to listOf(mapOf("a" to 1)))), "Amount\nin euro")
    }

    @Test
    fun `outside PDF A the defaults of a 1_x release still draw a Word line break as nothing, V8 breaks the line`() {
        val document = document(textNode("letter", text("Postbus 12\u000B1234 AB")))

        assertFalse(render(document, pdfa = false, defaults = RenderingDefaults.V7).contains("Postbus 12\n1234 AB"))
        assertContains(render(document, pdfa = false, defaults = RenderingDefaults.V8), "Postbus 12\n1234 AB")
    }
}

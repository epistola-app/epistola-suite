// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRefInherit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TextBlockExtractorTest {
    @Test
    fun `each paragraph and heading of a text node is a block, in order`() {
        val blocks = TextBlockExtractor.extract(
            document(
                "t" to doc(
                    heading(text("Your invoice")),
                    paragraph(text("Thank you.")),
                    mapOf("type" to "bulletList", "content" to listOf(mapOf("type" to "listItem", "content" to listOf(paragraph(text("One item.")))))),
                ),
            ),
        )

        assertThat(blocks.map { it.nodeId to it.text }).containsExactly(
            "t" to "Your invoice",
            "t" to "Thank you.",
            "t" to "One item.",
        )
    }

    @Test
    fun `an expression is a placeholder, one character in the text a checker reads and never its source`() {
        val block = TextBlockExtractor.extract(document("t" to doc(paragraph(text("Dear "), expression("customer.name"), text(","))))).single()

        assertThat(block.text).isEqualTo("Dear ￼,")
        assertThat(block.text).doesNotContain("customer")
        assertThat(block.display(0, block.text.length)).isEqualTo("Dear {customer.name},")
    }

    @Test
    fun `an expression written as raw and language reads as its raw source`() {
        val block = TextBlockExtractor.extract(
            document("t" to doc(paragraph(text("Hi "), mapOf("type" to "expression", "attrs" to mapOf("expression" to mapOf("raw" to "name", "language" to "jsonata")))))),
        ).single()

        assertThat(block.display(0, block.text.length)).isEqualTo("Hi {name}")
    }

    @Test
    fun `marks do not split words, and a hard break is a line break`() {
        val block = TextBlockExtractor.extract(
            document(
                "t" to doc(
                    paragraph(text("Pay"), mapOf("type" to "text", "text" to "ment", "marks" to listOf(mapOf("type" to "bold"))), mapOf("type" to "hardBreak"), text("due")),
                ),
            ),
        ).single()

        assertThat(block.text).isEqualTo("Payment\ndue")
        assertThat(block.parts).hasSize(1)
    }

    @Test
    fun `blocks with no letters are not checked`() {
        val blocks = TextBlockExtractor.extract(document("t" to doc(paragraph(), paragraph(expression("total")), paragraph(text("  42 ")))))

        assertThat(blocks).isEmpty()
    }

    @Test
    fun `only text nodes hold checkable text`() {
        val model = document("t" to doc(paragraph(text("Checked."))))
        val image = Node(id = "img", type = "image", slots = emptyList(), props = mapOf("alt" to "Not checked as prose"))
        val withImage = model.copy(nodes = model.nodes + (image.id to image))

        assertThat(TextBlockExtractor.extract(withImage).map { it.text }).containsExactly("Checked.")
    }

    @Test
    fun `a 1_x text node holding a plain string is one block`() {
        assertThat(TextBlockExtractor.blocksOf("t", "hello there").map { it.text }).containsExactly("hello there")
    }

    @Test
    fun `the hash is of the checkable text alone`() {
        val a = TextBlock("n1", listOf(TextPart.Literal("Dear "), TextPart.Placeholder("name"), TextPart.Literal(",")))
        val b = TextBlock("n2", listOf(TextPart.Literal("Dear "), TextPart.Placeholder("customer.fullName"), TextPart.Literal(",")))
        val c = TextBlock("n1", listOf(TextPart.Literal("Dear "), TextPart.Literal("X,")))

        // Another node and another expression: the same words, so the same result applies.
        assertThat(a.hash).isEqualTo(b.hash)
        // Literal text is never confused with a placeholder.
        assertThat(a.hash).isNotEqualTo(c.hash)
    }

    @Test
    fun `a range is literal text only when it is inside the block and touches no placeholder`() {
        val block = TextBlock("n", listOf(TextPart.Literal("Dear "), TextPart.Placeholder("name"), TextPart.Literal(", hello")))

        assertThat(block.coversOnlyLiteralText(0, 4)).isTrue()
        assertThat(block.coversOnlyLiteralText(3, 4)).isFalse()
        assertThat(block.coversOnlyLiteralText(8, 5)).isTrue()
        assertThat(block.coversOnlyLiteralText(10, 9)).isFalse()
        assertThat(block.coversOnlyLiteralText(-1, 2)).isFalse()
    }

    companion object {
        fun text(value: String) = mapOf("type" to "text", "text" to value)

        fun expression(source: String) = mapOf("type" to "expression", "attrs" to mapOf("expression" to source))

        fun paragraph(vararg inline: Map<String, Any>) = mapOf("type" to "paragraph", "content" to inline.toList())

        fun heading(vararg inline: Map<String, Any>) = mapOf("type" to "heading", "attrs" to mapOf("level" to 1), "content" to inline.toList())

        fun doc(vararg blocks: Map<String, Any>) = mapOf("type" to "doc", "content" to blocks.toList())

        /** A template whose root holds one `text` node per pair, each with that ProseMirror document. */
        fun document(vararg textNodes: Pair<String, Map<String, Any>>): TemplateDocument {
            val root = Node(id = "root", type = "root", slots = listOf("root-slot"))
            val nodes = textNodes.map { (id, content) -> Node(id = id, type = "text", slots = emptyList(), props = mapOf("content" to content)) }
            return TemplateDocument(
                modelVersion = 1,
                root = root.id,
                nodes = (listOf(root) + nodes).associateBy { it.id },
                slots = mapOf("root-slot" to Slot("root-slot", root.id, "children", nodes.map { it.id })),
                themeRef = ThemeRefInherit(),
            )
        }
    }
}

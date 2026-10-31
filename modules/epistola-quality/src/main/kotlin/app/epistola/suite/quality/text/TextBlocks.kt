// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.text

import app.epistola.suite.templates.model.TemplateDocument
import java.security.MessageDigest
import java.util.HexFormat

/** A run of a text block: literal text, or a placeholder standing for an expression's value. */
sealed interface TextPart {
    data class Literal(val value: String) : TextPart

    /** An expression. Its source is kept for showing the author, never sent to a checker. */
    data class Placeholder(val expression: String) : TextPart
}

/**
 * One paragraph-level block of a template's text: a paragraph or heading of a `text` node, as a
 * checker reads it (ADR 0044, section 3).
 *
 * [text] is what a checker sees: the literal text with each placeholder as a single
 * [PLACEHOLDER] character. A checker never learns which expression a placeholder stands for, let
 * alone a value, so example data and generated documents cannot reach one. Offsets in a
 * diagnostic are offsets into [text].
 *
 * [hash] identifies the block's checkable content and is the result cache's key: two blocks with the
 * same words share one result, whichever node, template or catalog version they are in, and
 * whichever expressions their placeholders stand for.
 */
data class TextBlock(
    val nodeId: String,
    val parts: List<TextPart>,
) {
    val text: String = parts.joinToString("") {
        when (it) {
            is TextPart.Literal -> it.value
            is TextPart.Placeholder -> PLACEHOLDER.toString()
        }
    }

    val hash: String = sha256("$HASH_VERSION\u0000$text")

    /** Whether [offset, offset + length) lies inside [text] and covers no placeholder. */
    fun coversOnlyLiteralText(offset: Int, length: Int): Boolean = offset >= 0 && length > 0 && offset + length <= text.length &&
        text.substring(offset, offset + length).none { it == PLACEHOLDER }

    /**
     * [text] between [from] and [to] as an author reads it: each placeholder shown as its
     * expression in braces. For evidence shown with a finding, never for a checker.
     */
    fun display(from: Int, to: Int): String {
        val start = from.coerceIn(0, text.length)
        val end = to.coerceIn(start, text.length)
        val out = StringBuilder()
        var position = 0
        for (part in parts) {
            when (part) {
                is TextPart.Literal -> {
                    val partEnd = position + part.value.length
                    val a = maxOf(start, position)
                    val b = minOf(end, partEnd)
                    if (a < b) out.append(part.value, a - position, b - position)
                    position = partEnd
                }

                is TextPart.Placeholder -> {
                    if (position in start until end) out.append('{').append(part.expression).append('}')
                    position += 1
                }
            }
        }
        return out.toString()
    }

    companion object {
        /** Stands for an expression in [text]: U+FFFC, the object replacement character. */
        const val PLACEHOLDER = '￼'

        /** Changes when [text] changes meaning, so a cached result is never read for different text. */
        private const val HASH_VERSION = "text-block/1"

        private fun sha256(value: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    }
}

/**
 * Extracts the [TextBlock]s of a template: every paragraph and heading of every `text` node, in the
 * model's node order. Pure, and defensive about shape, like every source: a template written by an
 * older editor yields what it can, never an exception.
 *
 * A stencil's text is not here: a template holds stencil instances by reference, so a stencil is
 * checked as itself rather than once more inside every template that uses it.
 */
object TextBlockExtractor {
    private const val TEXT_NODE_TYPE = "text"
    private const val EXPRESSION = "expression"
    private val INLINE_TYPES = setOf("text", EXPRESSION, "hardBreak")
    private val TEXTBLOCK_TYPES = setOf("paragraph", "heading")

    fun extract(model: TemplateDocument): List<TextBlock> = model.nodes.values
        .filter { it.type == TEXT_NODE_TYPE }
        .flatMap { node -> blocksOf(node.id, node.props?.get("content")) }

    fun blocksOf(nodeId: String, content: Any?): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        when (content) {
            // A 1.x text node could hold a plain string.
            is String -> blocks += TextBlock(nodeId, listOf(TextPart.Literal(content)))

            else -> collect(nodeId, content, blocks)
        }
        return blocks.filter { block -> block.parts.any { it is TextPart.Literal && it.value.any(Char::isLetter) } }
    }

    private fun collect(nodeId: String, node: Any?, blocks: MutableList<TextBlock>) {
        when (node) {
            is List<*> -> node.forEach { collect(nodeId, it, blocks) }

            is Map<*, *> -> {
                val children = node["content"] as? List<*> ?: emptyList<Any?>()
                val isTextblock = node["type"] in TEXTBLOCK_TYPES || children.any { (it as? Map<*, *>)?.get("type") in INLINE_TYPES }
                if (isTextblock) blocks += TextBlock(nodeId, inlineParts(children)) else collect(nodeId, children, blocks)
            }
        }
    }

    /** The inline content of a textblock as parts, adjacent literals merged so marks do not split words. */
    private fun inlineParts(children: List<*>): List<TextPart> {
        val parts = mutableListOf<TextPart>()
        fun literal(value: String) {
            val last = parts.lastOrNull()
            if (last is TextPart.Literal) parts[parts.lastIndex] = TextPart.Literal(last.value + value) else parts += TextPart.Literal(value)
        }
        for (child in children) {
            val map = child as? Map<*, *> ?: continue
            when (map["type"]) {
                "text" -> (map["text"] as? String)?.let(::literal)
                "hardBreak" -> literal("\n")
                EXPRESSION -> parts += TextPart.Placeholder(expressionSource((map["attrs"] as? Map<*, *>)?.get("expression")))
            }
        }
        return parts
    }

    /** An expression is its source text, or `{ raw, language }` in newer models. */
    private fun expressionSource(expression: Any?): String = when (expression) {
        is String -> expression
        is Map<*, *> -> expression["raw"] as? String ?: ""
        else -> ""
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

import app.epistola.template.model.TemplateDocument
import com.itextpdf.io.font.FontProgram
import com.itextpdf.pdfa.exceptions.PdfaExceptionMessageConstant

/**
 * A PDF/A render met a character that a font it is set in has no glyph for.
 *
 * iText refuses such a document ("The '' glyph isn't defined in embedded font") without saying
 * which character, which font or where: an invisible character even shows as `''`. This names
 * the code point, the fonts of the render that lack it, and where it occurs — a template block
 * with a short excerpt of its text, or a path into the data. Data is named by path only, never
 * quoted: it may be personal, and this message is logged and stored with the failed request.
 */
class UnrenderableCharacterException(
    val codePoint: Int,
    val fontsLacking: List<String>,
    val occurrences: List<String>,
    cause: Throwable,
) : IllegalArgumentException(message(codePoint, fontsLacking, occurrences), cause) {

    companion object {
        private const val MAX_OCCURRENCES = 5
        private const val EXCERPT_RADIUS = 20

        private val glyphMessage = PdfaExceptionMessageConstant.EMBEDDED_FONTS_SHALL_DEFINE_ALL_REFERENCED_GLYPHS
        private val glyphMessagePrefix = glyphMessage.substringBefore("{0}")
        private val glyphMessageSuffix = glyphMessage.substringAfter("{0}")

        /**
         * The clear form of [failure] when it, or one of its causes, is iText's PDF/A missing-glyph
         * refusal; null for any other failure, which the caller rethrows unchanged.
         */
        internal fun from(
            failure: Throwable,
            document: TemplateDocument,
            data: Map<String, Any?>,
            fonts: Collection<FontProgram>,
        ): UnrenderableCharacterException? {
            val refusal = generateSequence(failure) { it.cause }.firstOrNull { it.message?.startsWith(glyphMessagePrefix) == true }
                ?: return null
            val quoted = refusal.message!!.removePrefix(glyphMessagePrefix).removeSuffix(glyphMessageSuffix)
            if (quoted.isEmpty()) return null
            val codePoint = quoted.codePointAt(0)
            val lacking = fonts.filter { it.getGlyph(codePoint) == null }.map { it.fontNames.fontName }.distinct().sorted()
            val occurrences = (templateOccurrences(document, codePoint) + dataOccurrences(data, codePoint)).take(MAX_OCCURRENCES)
            return UnrenderableCharacterException(codePoint, lacking, occurrences, failure)
        }

        /** `U+000B (LINE TABULATION)`: the code point and its Unicode name. */
        fun describe(codePoint: Int): String {
            val name = Character.getName(codePoint) ?: "unassigned"
            return "U+%04X (%s)".format(codePoint, name)
        }

        private fun message(codePoint: Int, fontsLacking: List<String>, occurrences: List<String>): String = buildString {
            append("The text contains ").append(describe(codePoint)).append(", which ")
            when (fontsLacking.size) {
                0 -> append("a font it is set in")
                1 -> append("the font ").append(fontsLacking.single())
                else -> append("the fonts ").append(fontsLacking.joinToString(", "))
            }
            append(" has no glyph for. A PDF/A document can only show characters its embedded fonts define.")
            if (occurrences.isNotEmpty()) append(" Found in ").append(occurrences.joinToString("; ")).append('.')
            append(" Remove or replace the character, or set this text in a font that has it.")
        }

        private fun templateOccurrences(document: TemplateDocument, codePoint: Int): List<String> = document.nodes.values.flatMap { node ->
            strings(node.props).filter { it.containsCodePoint(codePoint) }.map { "${node.type} block ${node.id}: \"${excerpt(it, codePoint)}\"" }
        }

        private fun dataOccurrences(data: Map<String, Any?>, codePoint: Int): List<String> = buildList {
            fun walk(value: Any?, path: String) {
                when (value) {
                    is String -> if (value.containsCodePoint(codePoint)) add("the data at $path")
                    is Map<*, *> -> value.forEach { (key, child) -> walk(child, if (path.isEmpty()) "$key" else "$path.$key") }
                    is Iterable<*> -> value.forEachIndexed { index, child -> walk(child, "$path[$index]") }
                }
            }
            walk(data, "")
        }

        private fun strings(value: Any?): Sequence<String> = when (value) {
            is String -> sequenceOf(value)
            is Map<*, *> -> value.values.asSequence().flatMap { strings(it) }
            is Iterable<*> -> value.asSequence().flatMap { strings(it) }
            else -> emptySequence()
        }

        private fun String.containsCodePoint(codePoint: Int): Boolean = codePoints().anyMatch { it == codePoint }

        /** Up to [EXCERPT_RADIUS] characters either side of the first occurrence, the character itself as `[U+XXXX]`. */
        private fun excerpt(text: String, codePoint: Int): String {
            val points = text.codePoints().toArray()
            val at = points.indexOf(codePoint)
            val from = (at - EXCERPT_RADIUS).coerceAtLeast(0)
            val to = (at + EXCERPT_RADIUS + 1).coerceAtMost(points.size)
            fun show(slice: IntArray) = slice.joinToString("") { if (Character.isISOControl(it)) " " else String(Character.toChars(it)) }
            return buildString {
                if (from > 0) append('…')
                append(show(points.copyOfRange(from, at)))
                append("[U+%04X]".format(codePoint))
                append(show(points.copyOfRange(at + 1, to)))
                if (to < points.size) append('…')
            }
        }
    }
}

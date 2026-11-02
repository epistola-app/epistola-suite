// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.generation.pdf

/**
 * Turns the characters that text pasted from Word carries, and no font has a glyph for, into what
 * they mean: Word's manual line break and page break become a line break, its non-breaking and
 * optional hyphens a hyphen and a soft hyphen, its Symbol and Wingdings bullets a bullet. Other
 * control characters, except tab, line feed and carriage return, are removed.
 *
 * In a PDF/A document any of them fails the render (see [UnrenderableCharacterException]); in any
 * other PDF it is drawn as nothing. The table is `text-cleanup.tsv`, shared with the editor's paste
 * cleanup so a paste and a render agree.
 */
object TextCleanup {
    sealed interface Action {
        /** A line break: `\n`, which iText breaks the line at. */
        data object Break : Action

        data class Replace(val replacement: String) : Action
    }

    /** The table, by code point. */
    val actions: Map<Int, Action> = load()

    /** [text] with every character in [actions] replaced and other control characters removed; [text] itself when there is none. */
    fun clean(text: String): String {
        if (text.none { needsCleaning(it.code) }) return text
        return buildString(text.length) {
            text.codePoints().forEach { codePoint ->
                when (val action = actions[codePoint]) {
                    Action.Break -> append('\n')
                    is Action.Replace -> append(action.replacement)
                    null -> if (!isRemovedControl(codePoint)) appendCodePoint(codePoint)
                }
            }
        }
    }

    private fun needsCleaning(codePoint: Int): Boolean = codePoint in actions || isRemovedControl(codePoint)

    private fun isRemovedControl(codePoint: Int): Boolean = codePoint < 0x20 && codePoint != '\t'.code && codePoint != '\n'.code && codePoint != '\r'.code

    private fun load(): Map<Int, Action> {
        val lines = TextCleanup::class.java.getResourceAsStream("/app/epistola/generation/text-cleanup.tsv")
            ?.bufferedReader()?.use { it.readLines() }
            ?: error("text-cleanup.tsv is missing from the classpath")
        return lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
            val (codePoint, action, replacement) = line.split('\t')
            codePoint(codePoint) to when (action) {
                "break" -> Action.Break
                "replace" -> Action.Replace(String(Character.toChars(codePoint(replacement))))
                else -> error("text-cleanup.tsv: unknown action '$action' in: $line")
            }
        }
    }

    private fun codePoint(notation: String): Int = notation.removePrefix("U+").toInt(16)
}

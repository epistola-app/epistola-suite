// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Build-time gate for the corner-notice length contract (#477): every string
 * LITERAL passed to `successNotice(...)` / `errorNotice(...)` must fit the
 * notice limits — messages are one-sentence feedback, titles a few words.
 * Plain unit test — no Spring, no Docker — so it runs in the fast `unitTest`
 * cycle and gates every PR (same posture as [InputMaxLengthTest]).
 *
 * The limits are duplicated here on purpose (not imported from the DSL) so the
 * test catches the contract drifting on either side. Sibling oracle: the
 * `require()` guards in `HtmxResponseBuilder.notice()` (epistola-web), which
 * cover what this scan cannot — interpolated/computed strings — at first run.
 * Keep the two in step.
 *
 * Scanning is textual: literals inside comments and KDoc examples are checked
 * too, deliberately — examples should follow the contract they demonstrate.
 */
class NoticeLengthTest {

    private val messageMaxLength = 150
    private val titleMaxLength = 40

    private val stringLiteral = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val titleLiteral = Regex("title\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    @Test
    fun `notice message and title literals fit the notice limits`() {
        val violations = mutableListOf<String>()

        for (file in RepoSources.mainKotlinFiles()) {
            val text = Files.readString(file)
            for (call in listOf("successNotice(", "errorNotice(")) {
                var index = text.indexOf(call)
                while (index >= 0) {
                    val arguments = argumentSpan(text, index + call.length - 1)
                    if (arguments != null) {
                        val line = text.substring(0, index).count { it == '\n' } + 1
                        val where = "${RepoSources.relativize(file)}:$line"
                        violations += violationsIn(arguments, where)
                    }
                    index = text.indexOf(call, index + call.length)
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Notice literals exceeding the limits (message <= $messageMaxLength, title <= $titleMaxLength). " +
                "Shorten them — a notice is one-sentence feedback; longer content belongs in a dialog.\n" +
                violations.joinToString("\n"),
        )
    }

    /** Checks every string literal in one call's argument span; title= literals get the title limit. */
    private fun violationsIn(arguments: String, where: String): List<String> {
        val titleRanges = titleLiteral.findAll(arguments).map { it.groups[1]!!.range }.toList()
        return stringLiteral.findAll(arguments).mapNotNull { match ->
            val literal = match.groupValues[1]
            val isTitle = match.groups[1]!!.range in titleRanges
            val limit = if (isTitle) titleMaxLength else messageMaxLength
            if (literal.length > limit) {
                "$where: ${if (isTitle) "title" else "message"} literal of ${literal.length} chars (limit $limit): \"${literal.take(60)}…\""
            } else {
                null
            }
        }.toList()
    }

    /**
     * The text between the call's balanced parentheses, starting at [openParen].
     * Walks characters tracking string literals (with escapes) so parentheses
     * inside literals don't unbalance the span. Null if unbalanced (EOF).
     */
    private fun argumentSpan(text: String, openParen: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openParen until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                inString -> {}
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return text.substring(openParen + 1, i)
                }
            }
        }
        return null
    }
}

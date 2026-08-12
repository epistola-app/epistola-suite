// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.architecture

import app.epistola.suite.htmx.HtmxResponseBuilder.Companion.NOTICE_MESSAGE_MAX_LENGTH
import app.epistola.suite.htmx.HtmxResponseBuilder.Companion.NOTICE_TITLE_MAX_LENGTH
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
 * The limits come from the DSL's own constants; the runtime oracle is the
 * `require()` guards in `HtmxResponseBuilder.notice()` (epistola-web), which
 * cover what this scan cannot — interpolated/computed strings — at first run.
 * Comments are stripped before scanning, so prose and KDoc examples that
 * mention the helpers don't fail the build.
 */
class NoticeLengthTest {

    private val stringLiteral = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val namedArgument = Regex("^\\s*([a-zA-Z]\\w*)\\s*=[^=]")

    @Test
    fun `notice message and title literals fit the notice limits`() {
        val violations = mutableListOf<String>()

        for (file in RepoSources.mainKotlinFiles()) {
            val text = RepoSources.stripComments(Files.readString(file))
            for (call in listOf("successNotice(", "errorNotice(")) {
                var index = text.indexOf(call)
                while (index >= 0) {
                    val line = text.substring(0, index).count { it == '\n' } + 1
                    val where = "${RepoSources.relativize(file)}:$line"
                    val arguments = argumentSpan(text, index + call.length - 1)
                    if (arguments == null) {
                        violations += "$where: unbalanced parentheses after $call — fix the call or this scanner"
                    } else {
                        violations += violationsIn(arguments, where)
                    }
                    index = text.indexOf(call, index + call.length)
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Notice literals exceeding the limits (message <= $NOTICE_MESSAGE_MAX_LENGTH, title <= $NOTICE_TITLE_MAX_LENGTH). " +
                "Shorten them — a notice is one-sentence feedback; longer content belongs in a dialog.\n" +
                violations.joinToString("\n"),
        )
    }

    /**
     * Checks every string literal in one call's argument span. The title — the
     * second positional argument or a `title =` named one — gets the title
     * limit; everything else the message limit.
     */
    private fun violationsIn(arguments: String, where: String): List<String> {
        val violations = mutableListOf<String>()
        topLevelArguments(arguments).forEachIndexed { position, argument ->
            val name = namedArgument.find(argument)?.groupValues?.get(1)
            val isTitle = name == "title" || (name == null && position == 1)
            val limit = if (isTitle) NOTICE_TITLE_MAX_LENGTH else NOTICE_MESSAGE_MAX_LENGTH
            for (match in stringLiteral.findAll(argument)) {
                val literal = match.groupValues[1]
                if (literal.length > limit) {
                    violations +=
                        "$where: ${if (isTitle) "title" else "message"} literal of ${literal.length} chars (limit $limit): \"${literal.take(60)}…\""
                }
            }
        }
        return violations
    }

    /** Splits an argument span on top-level commas (outside strings, brackets, and lambdas). */
    private fun topLevelArguments(span: String): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (c in span) {
            when {
                escaped -> escaped = false
                inString && c == '\\' -> escaped = true
                c == '"' -> inString = !inString
                !inString && (c == '(' || c == '{' || c == '[') -> depth++
                !inString && (c == ')' || c == '}' || c == ']') -> depth--
                !inString && c == ',' && depth == 0 -> {
                    arguments += current.toString()
                    current.clear()
                    continue
                }
            }
            current.append(c)
        }
        arguments += current.toString()
        return arguments
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

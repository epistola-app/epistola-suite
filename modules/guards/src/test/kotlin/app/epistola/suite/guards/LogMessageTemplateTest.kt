// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.guards

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A log message is a constant pattern, written as a plain string literal; values go in as `{}` arguments. Pseudonymised telemetry sends
 * the pattern without its arguments (ADR 0052 §2), so a value built into the message with a string
 * template (`"… $email"`) or `+` would reach Epistola anyway.
 */
class LogMessageTemplateTest {
    /** The message: one string literal, or literals joined with `+`; group 2 is a `+` that joins something else. */
    private val logCall = Regex(
        """\b(?:log|logger|LOG|LOGGER)\.(?:trace|debug|info|warn|error)\(\s*("(?:[^"\\]|\\.)*"(?:\s*\+\s*"(?:[^"\\]|\\.)*")*)(\s*\+)?""",
    )
    private val template = Regex("""(?<!\\)\$[{a-zA-Z_]""")

    /** Any log call; the message must start right after it as a plain string literal. */
    private val anyLogCall = Regex("""\b(?:log|logger|LOG|LOGGER)\.(?:trace|debug|info|warn|error)\(\s*""")

    @Test
    fun `log messages carry no values, which go in as arguments`() {
        val files = RepoSources.mainKotlinFiles()
        var calls = 0
        val offenders = files.flatMap { file ->
            val source = RepoSources.stripComments(Files.readString(file))
            logCall.findAll(source).mapNotNull { match ->
                calls++
                val built = template.containsMatchIn(match.groupValues[1]) || match.groupValues[2].isNotEmpty()
                if (!built) return@mapNotNull null
                val line = source.substring(0, match.range.first).count { it == '\n' } + 1
                "${RepoSources.relativize(file)}:$line"
            }.toList()
        }

        // A computed message (`log.warn(mapping.message(ex))`, a raw string) is built wherever it is built,
        // so it has no pattern to send: it goes in as an argument of a constant pattern instead.
        val computed = files.flatMap { file ->
            val source = RepoSources.stripComments(Files.readString(file))
            anyLogCall.findAll(source).mapNotNull { match ->
                val rest = source.substring(match.range.last + 1)
                if (rest.startsWith("\"") && !rest.startsWith("\"\"\"")) return@mapNotNull null
                val line = source.substring(0, match.range.first).count { it == '\n' } + 1
                "${RepoSources.relativize(file)}:$line"
            }.toList()
        }

        assertTrue(calls > 100, "only $calls log calls found — the scan would pass vacuously")
        assertEquals(emptyList(), computed, "Give a log call a constant pattern: log.warn(\"{}\", message)")
        assertEquals(emptyList(), offenders, "Pass values as {} arguments: log.warn(\"Failed for {}\", key, e)")
    }
}

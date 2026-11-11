// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/** A stored server log shaped for its reader: pseudonymised as telemetry is, or in full, never with its patterns. */
class ServerLogExcerptTest {
    private val mapper = JsonMapper.builder().build()

    private val stored = """
        [
          {"time":"2026-11-11T09:00:00Z","level":"ERROR","logger":"app.epistola.suite.Example","message":"Render failed for jan@example.org","pattern":"Render failed for {}","traceId":"t1",
           "exception":"java.lang.IllegalStateException: customer Jan Jansen not found\n\tat app.epistola.suite.Example.render(Example.kt:12)"},
          {"level":"WARN","logger":"org.springframework.web.Resolver","message":"Invalid UUID string: jan@example.org","traceId":"t1"},
          {"level":"INFO","logger":"app.epistola.suite.Other","message":"Signed in as jan@example.org","traceId":"t1"},
          {"level":"INFO","message":"… more lines were left out","pattern":"… more lines were left out"}
        ]
    """.trimIndent()

    @Test
    fun `pseudonymised, the suite's lines keep their pattern, a library's message is withheld and exceptions lose their messages`() {
        val lines = mapper.readTree(ServerLogExcerpt.shaped(stored, pseudonymised = true, mapper))

        assertThat(lines.toString()).doesNotContain("jan@example.org").doesNotContain("Jan Jansen").doesNotContain("\"pattern\"")
        assertThat(lines[0].path("message").stringValue()).isEqualTo("Render failed for {}")
        assertThat(lines[0].path("exception").stringValue()).isEqualTo("java.lang.IllegalStateException\n\tat app.epistola.suite.Example.render(Example.kt:12)")
        assertThat(lines[0].path("traceId").stringValue()).isEqualTo("t1")
        assertThat(lines[1].path("message").stringValue()).isEqualTo(ServerLogExcerpt.LIBRARY_MESSAGE_WITHHELD)
        assertThat(lines[2].path("message").stringValue()).`as`("kept without its pattern: no message rather than its values").isEqualTo(ServerLogExcerpt.MESSAGE_NOT_KEPT)
        assertThat(lines[3].path("message").stringValue()).isEqualTo("… more lines were left out")
    }

    @Test
    fun `in full, every message and exception goes as recorded, without the patterns`() {
        val lines = mapper.readTree(ServerLogExcerpt.shaped(stored, pseudonymised = false, mapper))

        assertThat(lines.toString()).doesNotContain("\"pattern\"")
        assertThat(lines[0].path("message").stringValue()).isEqualTo("Render failed for jan@example.org")
        assertThat(lines[0].path("exception").stringValue()).contains("customer Jan Jansen not found")
        assertThat(lines[1].path("message").stringValue()).isEqualTo("Invalid UUID string: jan@example.org")
    }

    @Test
    fun `text that is not a list of lines is withheld whole when pseudonymised`() {
        assertThat(ServerLogExcerpt.shaped("jan@example.org", pseudonymised = true, mapper)).isEqualTo("[]")
        assertThat(ServerLogExcerpt.shaped("jan@example.org", pseudonymised = false, mapper)).isEqualTo("jan@example.org")
    }

    @Test
    fun `a stack trace loses its messages and keeps its types and frames`() {
        val trace = """
            java.lang.IllegalStateException: customer Jan Jansen not found
            	at app.epistola.suite.Example.render(Example.kt:12)
            	at app.epistola.suite.Example.run(Example.kt:5)
            Caused by: java.lang.IllegalArgumentException: BSN 123456782
            	at app.epistola.suite.Lookup.find(Lookup.kt:3)
            	... 2 common frames omitted
        """.trimIndent()

        val stripped = ServerLogExcerpt.withoutMessages(trace)

        assertThat(stripped).isEqualTo(
            """
            java.lang.IllegalStateException
            	at app.epistola.suite.Example.render(Example.kt:12)
            	at app.epistola.suite.Example.run(Example.kt:5)
            Caused by: java.lang.IllegalArgumentException
            	at app.epistola.suite.Lookup.find(Lookup.kt:3)
            	... 2 common frames omitted
            """.trimIndent(),
        )
    }
}

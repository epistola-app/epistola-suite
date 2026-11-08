// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.adapters

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Pseudonymised, an attached stack trace keeps its exception types and frames, not their messages. */
class ApplicationLogSupportRequestSourceTest {
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

        val stripped = ApplicationLogSupportRequestSource.withoutMessages(trace)

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

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.core.OutputStreamAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

/**
 * Loads the production `logback-spring.xml` (plain-text console) and checks what a log line looks
 * like. A logged value carrying a line break must not start a new line: in plain text that would
 * let a user-supplied value forge a log entry (CWE-117).
 */
class ConsoleLogPatternTest {

    @Test
    fun `a line feed in a logged value stays on one line, escaped`() {
        val output = log("Created template name=[{}]", "x]\n2026-11-11T10:00:00 INFO forged=[admin]")

        assertThat(output.lines().filter { it.isNotEmpty() }).hasSize(1)
        assertThat(output).contains("""name=[x]\n2026-11-11T10:00:00 INFO forged=[admin]]""")
    }

    @Test
    fun `a carriage return and line feed collapse into one escape`() {
        val output = log("value=[{}]", "a\r\nb\rc")

        assertThat(output.lines().filter { it.isNotEmpty() }).hasSize(1)
        assertThat(output).contains("""value=[a\nb\nc]""")
    }

    @Test
    fun `a stack trace still spans several lines`() {
        val output = log("Failed", RuntimeException("boom"))

        assertThat(output.lines().first()).endsWith("Failed")
        assertThat(output).contains("java.lang.RuntimeException: boom")
        assertThat(output.lines().filter { it.isNotEmpty() }.size).isGreaterThan(1)
    }

    private fun log(message: String, vararg args: Any): String {
        // A fresh context, not the test classpath's one, so logback-test.xml plays no part.
        val context = LoggerContext().apply { mdcAdapter = LogbackMDCAdapter() }
        JoranConfigurator().apply { this.context = context }
            .doConfigure(requireNotNull(javaClass.getResource("/logback-spring.xml")))
        val buffer = ByteArrayOutputStream()

        @Suppress("UNCHECKED_CAST")
        val console = context.getLogger("ROOT").getAppender("CONSOLE") as OutputStreamAppender<Any>
        console.setOutputStream(buffer)
        try {
            context.getLogger("test").info(message, *args)
        } finally {
            context.stop()
        }
        return buffer.toString(Charsets.UTF_8)
    }
}

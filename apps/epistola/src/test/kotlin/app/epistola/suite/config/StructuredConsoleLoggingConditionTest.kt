// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import ch.qos.logback.classic.LoggerContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StructuredConsoleLoggingConditionTest {
    @Test
    fun `structured logging is disabled when no console format is configured`() {
        assertThat(condition(LoggerContext()).evaluate()).isFalse()
    }

    @Test
    fun `structured logging is enabled when a console format is configured`() {
        val context = LoggerContext().apply {
            putProperty(StructuredConsoleLoggingCondition.CONSOLE_FORMAT_PROPERTY, "logstash")
        }

        assertThat(condition(context).evaluate()).isTrue()
    }

    private fun condition(context: LoggerContext) = StructuredConsoleLoggingCondition().apply {
        this.context = context
        localPropertyContainer = context
        start()
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Severity
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor
import io.opentelemetry.sdk.testing.exporter.InMemoryLogRecordExporter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class TelemetryLogAppenderTest {
    private val exporter = InMemoryLogRecordExporter.create()
    private val provider =
        SdkLoggerProvider
            .builder()
            .addLogRecordProcessor(SimpleLogRecordProcessor.create(exporter))
            .build()
    private val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext

    /** Attach the appender to an isolated (non-additive) Logback logger and return it, ready to emit. */
    private fun loggerWith(
        name: String,
        privacy: TelemetryPrivacy = TelemetryPrivacy.FULL,
        shouldForward: (String?) -> Boolean,
    ): Logger {
        val appender =
            TelemetryLogAppender(provider.get("test"), shouldForward, { privacy }).apply {
                context = loggerContext
                start()
            }
        return loggerContext.getLogger(name).apply {
            level = Level.TRACE
            isAdditive = false
            addAppender(appender)
        }
    }

    @Test
    fun `forwards a passing event with severity, body and logger attribute`() {
        loggerWith("com.example.Service") { true }.warn("rendered ok")

        val records = exporter.finishedLogRecordItems
        assertThat(records).hasSize(1)
        val record = records.single()
        assertThat(record.severity).isEqualTo(Severity.WARN)
        assertThat(record.attributes.get(AttributeKey.stringKey("logger.name"))).isEqualTo("com.example.Service")
        assertThat(record.attributes.get(AttributeKey.stringKey("thread.name"))).isEqualTo(Thread.currentThread().name)
    }

    @Test
    fun `a long message and a deep stack trace are cut, so a queued record stays small`() {
        fun deep(depth: Int): Nothing = if (depth == 0) throw IllegalStateException("database unreachable") else deep(depth - 1)
        val failure = runCatching { deep(2_000) }.exceptionOrNull()!!

        loggerWith("com.example.Service") { true }.error("x".repeat(100_000), failure)

        val record = exporter.finishedLogRecordItems.single()
        val stack = record.attributes.get(AttributeKey.stringKey("exception.stacktrace"))!!
        assertThat(stack).startsWith("java.lang.IllegalStateException: database unreachable").contains("[truncated:")
        assertThat(stack.length).isLessThanOrEqualTo(TelemetryLogAppender.MAX_STACKTRACE_CHARS + 64)
        val body = record.bodyValue!!.asString()
        assertThat(body).contains("[truncated:")
        assertThat(body.length).isLessThanOrEqualTo(TelemetryLogAppender.MAX_BODY_CHARS + 64)
    }

    @Test
    fun `pseudonymised, a record carries the message pattern, the exception's types and frames, and no thread name`() {
        val pseudonymised = TelemetryPrivacy(pseudonymised = true, tenantOnWire = { "t-$it" })
        val failure = IllegalStateException("customer Jan Jansen not found", IllegalArgumentException("BSN 123456782"))

        loggerWith("app.epistola.suite.Service", pseudonymised) { true }.warn("Render failed for {} at {}", "jan@example.org", "letter", failure)

        val record = exporter.finishedLogRecordItems.single()
        assertThat(record.bodyValue!!.asString()).isEqualTo("Render failed for {} at {}")
        val stack = record.attributes.get(AttributeKey.stringKey("exception.stacktrace"))!!
        assertThat(stack).startsWith("java.lang.IllegalStateException\n\tat ").contains("Caused by: java.lang.IllegalArgumentException")
            .doesNotContain("Jan Jansen").doesNotContain("123456782")
        assertThat(record.attributes.get(AttributeKey.stringKey("thread.name"))).isNull()
    }

    @Test
    fun `pseudonymised, below WARN only the suite's own loggers are forwarded`() {
        val pseudonymised = TelemetryPrivacy(pseudonymised = true, tenantOnWire = { it })

        loggerWith("com.zaxxer.hikari.Pool", pseudonymised) { true }.info("connected as {}", "admin")
        loggerWith("com.zaxxer.hikari.Other", pseudonymised) { true }.warn("slow")
        loggerWith("app.epistola.suite.Mine", pseudonymised) { true }.info("mine")

        assertThat(exporter.finishedLogRecordItems.map { it.bodyValue!!.asString() }).containsExactly("slow", "mine")
    }

    @Test
    fun `everything, a record carries the formatted message`() {
        loggerWith("com.example.Service") { true }.warn("Render failed for {}", "letter")

        assertThat(exporter.finishedLogRecordItems.single().bodyValue!!.asString()).isEqualTo("Render failed for letter")
    }

    @Test
    fun `drops an event the policy rejects`() {
        loggerWith("com.example.Service") { false }.info("withheld")
        assertThat(exporter.finishedLogRecordItems).isEmpty()
    }

    @Test
    fun `ignores OpenTelemetry's own loggers to avoid an export-failure support request loop`() {
        loggerWith("io.opentelemetry.exporter.internal.http.HttpExporter") { true }.warn("export failed")
        assertThat(exporter.finishedLogRecordItems).isEmpty()
    }
}

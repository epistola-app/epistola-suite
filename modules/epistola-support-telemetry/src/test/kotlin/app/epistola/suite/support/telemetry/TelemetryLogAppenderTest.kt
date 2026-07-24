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
        shouldForward: (String?) -> Boolean,
    ): Logger {
        val appender =
            TelemetryLogAppender(provider.get("test"), shouldForward).apply {
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
    fun `drops an event the policy rejects`() {
        loggerWith("com.example.Service") { false }.info("withheld")
        assertThat(exporter.finishedLogRecordItems).isEmpty()
    }

    @Test
    fun `ignores OpenTelemetry's own loggers to avoid an export-failure feedback loop`() {
        loggerWith("io.opentelemetry.exporter.internal.http.HttpExporter") { true }.warn("export failed")
        assertThat(exporter.finishedLogRecordItems).isEmpty()
    }
}

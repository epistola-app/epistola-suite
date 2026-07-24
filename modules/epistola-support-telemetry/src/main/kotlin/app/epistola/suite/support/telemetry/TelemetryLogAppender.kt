// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.security.SecurityContext
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.time.Instant

/**
 * Logback appender that forwards each log event to the hub as an OTLP log record, via the dedicated
 * OpenTelemetry [Logger] of the telemetry leg. This is the leg's **producer-side control point**
 * (ADR 0006): it runs on the logging thread (so the request's tenant context is still bound),
 * applies the [shouldForward] policy — the installation-wide gate and the per-tenant DENY opt-out —
 * and only emits records that pass. Conversion is cheap and never throws into the caller; the OTLP
 * `BatchLogRecordProcessor` behind [otelLogger] owns batching, compression, and drop-on-overflow.
 *
 * Recursion guard: events from OpenTelemetry's own loggers and from this package are ignored, so an
 * export-failure warning can never feed back into the exporter and amplify.
 */
class TelemetryLogAppender(
    private val otelLogger: Logger,
    private val shouldForward: (tenantKey: String?) -> Boolean,
) : AppenderBase<ILoggingEvent>() {
    public override fun append(event: ILoggingEvent) {
        val loggerName = event.loggerName ?: ""
        if (loggerName.startsWith(OTEL_PACKAGE) || loggerName.startsWith(SELF_PACKAGE)) return

        val tenantKey = runCatching { SecurityContext.currentOrNull()?.currentTenantId?.value }.getOrNull()
        if (!shouldForward(tenantKey)) return

        val attributes = Attributes.builder()
        attributes.put("logger.name", loggerName)
        event.threadName?.let { attributes.put("thread.name", it) }
        tenantKey?.let { attributes.put(TENANT_ATTRIBUTE, it) }
        val mdc = event.mdcPropertyMap ?: emptyMap()
        mdc[TRACE_ID_KEY]?.let { attributes.put("trace_id", it) }
        mdc[SPAN_ID_KEY]?.let { attributes.put("span_id", it) }
        event.throwableProxy?.let { attributes.put("exception.stacktrace", ThrowableProxyUtil.asString(it)) }

        otelLogger
            .logRecordBuilder()
            .setTimestamp(Instant.ofEpochMilli(event.timeStamp))
            .setSeverity(severityOf(event.level))
            .setSeverityText(event.level?.toString() ?: "")
            .setBody(event.formattedMessage ?: "")
            .setAllAttributes(attributes.build())
            .emit()
    }

    private fun severityOf(level: Level?): Severity = when (level?.levelInt) {
        Level.ERROR_INT -> Severity.ERROR
        Level.WARN_INT -> Severity.WARN
        Level.INFO_INT -> Severity.INFO
        Level.DEBUG_INT -> Severity.DEBUG
        Level.TRACE_INT -> Severity.TRACE
        else -> Severity.UNDEFINED_SEVERITY_NUMBER
    }

    companion object {
        /** Per-record tenant attribute on forwarded log records. */
        const val TENANT_ATTRIBUTE = "epistola.tenant"
        private const val OTEL_PACKAGE = "io.opentelemetry"
        private const val SELF_PACKAGE = "app.epistola.suite.support.telemetry"
        private const val TRACE_ID_KEY = "traceId"
        private const val SPAN_ID_KEY = "spanId"
    }
}

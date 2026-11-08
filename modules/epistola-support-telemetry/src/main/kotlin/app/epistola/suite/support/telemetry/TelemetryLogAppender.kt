// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.security.SecurityContext
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity
import java.time.Instant

/**
 * Logback appender that forwards each log event to the support service as an OTLP log record, via the dedicated
 * OpenTelemetry [Logger] of the telemetry leg ([SupportTelemetryLogs]). It runs on the logging thread, so the
 * request's tenant context is still bound, and emits only the records [shouldForward] lets through — the leg
 * passes its cached `telemetry` scope flag, so nothing is converted while the scope is not granted. Conversion
 * is cheap and never throws into the caller; the OTLP `BatchLogRecordProcessor` behind [otelLogger] owns
 * batching, compression, and drop-on-overflow.
 *
 * Recursion guard: events from OpenTelemetry's own loggers and from this package are ignored, so an
 * export-failure warning can never feed back into the exporter and amplify.
 *
 * Size bound: the message is cut at [MAX_BODY_CHARS] and the stack trace at [MAX_STACKTRACE_CHARS],
 * so a queued record stays a few KiB however deep the stack. The queue is bounded by count; this
 * bounds it in bytes, also while the support service is slow and an error storm fills it.
 */
class TelemetryLogAppender(
    private val otelLogger: Logger,
    private val shouldForward: (tenantKey: String?) -> Boolean,
    private val privacy: () -> TelemetryPrivacy = { TelemetryPrivacy.FULL },
) : AppenderBase<ILoggingEvent>() {
    public override fun append(event: ILoggingEvent) {
        val loggerName = event.loggerName ?: ""
        if (loggerName.startsWith(OTEL_PACKAGE) || loggerName.startsWith(SELF_PACKAGE)) return

        val tenantKey = runCatching { SecurityContext.currentOrNull()?.currentTenantId?.value }.getOrNull()
        if (!shouldForward(tenantKey)) return
        val mode = privacy()
        // Pseudonymised: below WARN only the suite's own lines, whose message patterns carry no values
        // (LogMessageTemplateTest); a library may format values into its message itself.
        if (mode.pseudonymised && !loggerName.startsWith(OWN_PACKAGE) && (event.level?.levelInt ?: 0) < Level.WARN_INT) return

        val attributes = Attributes.builder()
        attributes.put("logger.name", loggerName)
        if (!mode.pseudonymised) event.threadName?.let { attributes.put("thread.name", it) }
        tenantKey?.let { attributes.put(TENANT_ATTRIBUTE, mode.tenantOnWire(it)) }
        val mdc = event.mdcPropertyMap ?: emptyMap()
        mdc[TRACE_ID_KEY]?.let { attributes.put("trace_id", it) }
        mdc[SPAN_ID_KEY]?.let { attributes.put("span_id", it) }
        mdc[COMMAND_KEY]?.let { attributes.put("epistola.command", it) }
        mdc[QUERY_KEY]?.let { attributes.put("epistola.query", it) }
        event.throwableProxy?.let {
            val trace = if (mode.pseudonymised) withoutMessages(it) else ThrowableProxyUtil.asString(it)
            attributes.put("exception.stacktrace", cut(trace, MAX_STACKTRACE_CHARS))
        }
        // Pseudonymised: the message as written in the code, without the values filled in.
        val body = if (mode.pseudonymised) event.message else event.formattedMessage

        otelLogger
            .logRecordBuilder()
            .setTimestamp(Instant.ofEpochMilli(event.timeStamp))
            .setSeverity(severityOf(event.level))
            .setSeverityText(event.level?.toString() ?: "")
            .setBody(cut(body ?: "", MAX_BODY_CHARS))
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
        /**
         * An exception as its types and stack frames, causes and suppressed ones included, without any
         * message: exception messages often quote the data that failed (ADR 0052 §2).
         */
        internal fun withoutMessages(proxy: IThrowableProxy): String = buildString {
            fun write(p: IThrowableProxy, prefix: String, depth: Int) {
                if (depth > MAX_CAUSE_DEPTH) return
                append(prefix).append(p.className).append('\n')
                p.stackTraceElementProxyArray?.forEach { append("\tat ").append(it.stackTraceElement).append('\n') }
                p.suppressed?.forEach { write(it, "Suppressed: ", depth + 1) }
                p.cause?.takeIf { it !== p }?.let { write(it, "Caused by: ", depth + 1) }
            }
            write(proxy, "", 0)
        }

        /** The longest message forwarded; the rest is cut. */
        const val MAX_BODY_CHARS = 4_096

        /** The longest stack trace forwarded: its top, where the cause is named; the rest is cut. */
        const val MAX_STACKTRACE_CHARS = 8_192

        /** [value] at most [max] characters, with a note of how much was cut. */
        internal fun cut(value: String, max: Int): String = if (value.length <= max) value else value.take(max) + "\n… [truncated: ${value.length - max} more characters]"

        /** Per-record tenant attribute on forwarded log records. */
        const val TENANT_ATTRIBUTE = "epistola.tenant"
        private const val OTEL_PACKAGE = "io.opentelemetry"
        private const val OWN_PACKAGE = "app.epistola."
        private const val COMMAND_KEY = "command"
        private const val QUERY_KEY = "query"
        private const val MAX_CAUSE_DEPTH = 16
        private const val SELF_PACKAGE = "app.epistola.suite.support.telemetry"
        private const val TRACE_ID_KEY = "traceId"
        private const val SPAN_ID_KEY = "spanId"
    }
}

/**
 * How a log line is turned into a record (ADR 0052 §1–2).
 *
 * @property pseudonymised send the message pattern without its values, exceptions without messages, no thread names.
 * @property tenantOnWire what is sent for a tenant key: the key itself, or its reference.
 */
data class TelemetryPrivacy(val pseudonymised: Boolean, val tenantOnWire: (String) -> String) {
    companion object {
        val FULL = TelemetryPrivacy(pseudonymised = false, tenantOnWire = { it })
    }
}

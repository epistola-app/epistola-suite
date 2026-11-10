// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.privacy.SupportPrivacySettings
import app.epistola.suite.support.privacy.TenantReferences
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
 * Logback appender that forwards each log event as an OTLP log record to every support connection that takes
 * telemetry, via the dedicated OpenTelemetry [Logger] of each ([SupportTelemetryLogs]), converted with that
 * connection's privacy. It runs on the logging thread, so the request's tenant context is still bound, and emits
 * only to the [outputs] the leg holds — those whose `telemetry` scope it found granted, cached, so nothing is
 * converted while no connection grants it. Conversion
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
    /** Where each event goes now: one output per connection that takes telemetry, each with its own privacy. */
    private val outputs: () -> List<Output>,
) : AppenderBase<ILoggingEvent>() {
    /** One connection's logger and how a line is turned into a record for it. */
    class Output(val otelLogger: Logger, val privacy: () -> TelemetryPrivacy)

    /** One output: [otelLogger] while [shouldForward] says so, with [privacy]. */
    constructor(
        otelLogger: Logger,
        shouldForward: (tenantKey: String?) -> Boolean,
        privacy: () -> TelemetryPrivacy = { TelemetryPrivacy.FULL },
    ) : this({
        val tenantKey = runCatching { SecurityContext.currentOrNull()?.currentTenantId?.value }.getOrNull()
        if (shouldForward(tenantKey)) listOf(Output(otelLogger, privacy)) else emptyList()
    })

    public override fun append(event: ILoggingEvent) {
        val loggerName = event.loggerName ?: ""
        if (loggerName.startsWith(OTEL_PACKAGE) || loggerName.startsWith(SELF_PACKAGE)) return
        val targets = outputs()
        if (targets.isEmpty()) return
        val tenantKey = runCatching { SecurityContext.currentOrNull()?.currentTenantId?.value }.getOrNull()
        targets.forEach { emit(event, loggerName, tenantKey, it) }
    }

    private fun emit(event: ILoggingEvent, loggerName: String, tenantKey: String?, output: Output) {
        val mode = output.privacy()
        // Pseudonymised: below WARN only the suite's own lines, whose message patterns carry no values
        // (LogMessageTemplateTest); a library may format values into its message itself.
        if (mode.pseudonymised && !loggerName.startsWith(OWN_PACKAGE) && (event.level?.levelInt ?: 0) < Level.WARN_INT) return

        val attributes = Attributes.builder()
        attributes.put("logger.name", loggerName)
        if (!mode.pseudonymised) event.threadName?.let { attributes.put("thread.name", it) }
        tenantKey?.let(mode.tenantOnWire)?.let { attributes.put(TENANT_ATTRIBUTE, it) }
        val mdc = event.mdcPropertyMap ?: emptyMap()
        mdc[TRACE_ID_KEY]?.let { attributes.put("trace_id", it) }
        mdc[SPAN_ID_KEY]?.let { attributes.put("span_id", it) }
        mdc[COMMAND_KEY]?.let { attributes.put("epistola.command", it) }
        mdc[QUERY_KEY]?.let { attributes.put("epistola.query", it) }
        event.throwableProxy?.let {
            val trace = if (mode.pseudonymised) withoutMessages(it) else ThrowableProxyUtil.asString(it)
            attributes.put("exception.stacktrace", cut(trace, MAX_STACKTRACE_CHARS))
        }
        // Pseudonymised: the suite's own message as written in the code, without the values filled in. A
        // library's message goes not at all: a library may have built the values into it itself.
        val body = when {
            !mode.pseudonymised -> event.formattedMessage
            loggerName.startsWith(OWN_PACKAGE) -> event.message
            else -> WITHHELD_BODY
        }

        output.otelLogger
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

        /** The body of a library's line in pseudonymised mode: its logger, level and stack frames still go. */
        const val WITHHELD_BODY = "(library message withheld)"

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
 * @property tenantOnWire what is sent for a tenant key: the key itself, its reference, or null for nothing.
 */
data class TelemetryPrivacy(val pseudonymised: Boolean, val tenantOnWire: (String) -> String?) {
    companion object {
        val FULL = TelemetryPrivacy(pseudonymised = false, tenantOnWire = { it })

        /**
         * The one place logs and metrics take the privacy settings from. Named, a tenant goes as its key.
         * Confidential, it goes as its reference once this node knows it, and otherwise not at all: never
         * as its key, and never by reading the database on the thread that logged.
         */
        fun of(settings: SupportPrivacySettings, references: TenantReferences?): TelemetryPrivacy = TelemetryPrivacy(
            settings.pseudonymised,
            if (settings.confidential) { key -> references?.referenceIfReady(key) } else { key -> key },
        )
    }
}

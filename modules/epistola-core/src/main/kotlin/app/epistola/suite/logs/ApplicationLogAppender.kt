package app.epistola.suite.logs

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.security.SecurityContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.AppenderBase
import java.time.Instant
import java.time.ZoneOffset

/**
 * Logback appender that converts each log event into an [ApplicationLogRecord]
 * and offers it to the [ApplicationLogIngestor] for asynchronous, batched
 * persistence.
 *
 * Conversion happens here, synchronously on the **thread that logged**, because
 * the per-request tenant context ([SecurityContext], a `ScopedValue`) and the
 * MDC are only bound on that thread — the ingestor's drain thread cannot see
 * them. The work is intentionally cheap: build an immutable record and a
 * non-blocking `offer`. Nothing here blocks the caller or throws into it.
 *
 * Recursion guard: events from this feature's own package (e.g. a persistence
 * failure logged by the ingestor) are ignored, so a DB outage cannot feed its
 * own error logs back into the queue.
 *
 * Decoupled from the ingestor via a [sink] function so the conversion can be
 * unit-tested without a queue or database.
 */
class ApplicationLogAppender(
    private val sink: (ApplicationLogRecord) -> Unit,
) : AppenderBase<ILoggingEvent>() {

    public override fun append(event: ILoggingEvent) {
        val loggerName = event.loggerName ?: ""
        if (loggerName.startsWith(SELF_PACKAGE)) return

        val mdc = event.mdcPropertyMap ?: emptyMap()
        val attributes = mdc
            .filterKeys { it != TRACE_ID_KEY && it != SPAN_ID_KEY }
            .takeIf { it.isNotEmpty() }

        sink(
            ApplicationLogRecord(
                id = UUIDv7.generate(),
                occurredAt = Instant.ofEpochMilli(event.timeStamp).atOffset(ZoneOffset.UTC),
                level = event.level?.toString() ?: "UNKNOWN",
                logger = loggerName,
                message = event.formattedMessage ?: "",
                thread = event.threadName,
                tenantKey = resolveTenantKey(mdc),
                traceId = mdc[TRACE_ID_KEY],
                spanId = mdc[SPAN_ID_KEY],
                exception = event.throwableProxy?.let { ThrowableProxyUtil.asString(it) },
                attributes = attributes,
            ),
        )
    }

    /**
     * Best-effort tenant attribution: the active request tenant when bound, else
     * an MDC hint, else null (a system/background log). Never throws.
     */
    private fun resolveTenantKey(mdc: Map<String, String>): String? = runCatching {
        SecurityContext.currentOrNull()?.currentTenantId?.value
    }.getOrNull() ?: mdc[MDC_TENANT_KEY] ?: mdc[MDC_TENANT_ID]

    companion object {
        /** Events from this package are never captured (recursion guard). */
        const val SELF_PACKAGE = "app.epistola.suite.logs"
        private const val TRACE_ID_KEY = "traceId"
        private const val SPAN_ID_KEY = "spanId"
        private const val MDC_TENANT_KEY = "tenantKey"
        private const val MDC_TENANT_ID = "tenantId"
    }
}

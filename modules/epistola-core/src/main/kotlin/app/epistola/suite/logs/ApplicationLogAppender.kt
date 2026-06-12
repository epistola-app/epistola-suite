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
 * Recursion guard: the [ApplicationLogIngestor]'s own logger is ignored, so its
 * persistence-failure warnings cannot feed back into the queue during a DB
 * outage. It is scoped to that one logger (not the whole `logs` package) so the
 * rest of the feature's diagnostics — e.g. the query handler — are still captured.
 *
 * Decoupled from the ingestor via a [sink] function so the conversion can be
 * unit-tested without a queue or database.
 */
class ApplicationLogAppender(
    private val sink: (ApplicationLogRecord) -> Unit,
) : AppenderBase<ILoggingEvent>() {

    public override fun append(event: ILoggingEvent) {
        val loggerName = event.loggerName ?: ""
        if (loggerName == INGESTOR_LOGGER) return

        val mdc = event.mdcPropertyMap ?: emptyMap()
        val attributes = mdc
            .filterKeys { it != TRACE_ID_KEY && it != SPAN_ID_KEY }
            .takeIf { it.isNotEmpty() }

        sink(
            ApplicationLogRecord(
                id = UUIDv7.generate(),
                // Logback's wall-clock event time (epoch millis), not EpistolaClock — stored UTC;
                // the UI renders it in the viewer's browser timezone.
                occurredAt = Instant.ofEpochMilli(event.timeStamp).atOffset(ZoneOffset.UTC),
                level = event.level?.toString() ?: "UNKNOWN",
                logger = loggerName,
                message = event.formattedMessage ?: "",
                thread = event.threadName,
                tenantKey = resolveTenantKey(),
                traceId = mdc[TRACE_ID_KEY],
                spanId = mdc[SPAN_ID_KEY],
                exception = event.throwableProxy?.let { ThrowableProxyUtil.asString(it) },
                attributes = attributes,
            ),
        )
    }

    /**
     * Tenant attribution from the single source of truth: the active principal's
     * `currentTenantId` (a `ScopedValue`), read here on the **logging thread**.
     * It is propagated across request and background threads by the security/mediator
     * context, so background work that wants its logs attributed must run under a bound
     * principal (e.g. `SystemUser.principalForTenant(...)`; see issue #551). Null when no
     * principal is bound — a system/background log. Never throws.
     */
    private fun resolveTenantKey(): String? = runCatching {
        SecurityContext.currentOrNull()?.currentTenantId?.value
    }.getOrNull()

    companion object {
        /** The one logger excluded from capture (recursion guard) — see class KDoc. */
        const val INGESTOR_LOGGER = "app.epistola.suite.logs.ApplicationLogIngestor"
        private const val TRACE_ID_KEY = "traceId"
        private const val SPAN_ID_KEY = "spanId"
    }
}

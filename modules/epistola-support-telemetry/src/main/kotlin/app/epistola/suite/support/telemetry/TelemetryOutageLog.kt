// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import io.opentelemetry.exporter.internal.FailedExportException
import org.slf4j.Logger
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Filter
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * How one telemetry leg logs the support service's availability, on this node. The support service
 * is optional and telemetry lossy, so an outage is news once, not every export: one WARN when exports
 * start failing, one INFO when one succeeds again, and DEBUG for every failure in between. An
 * expected failure — the service unreachable, a deadline, an HTTP answer — is logged without a stack
 * trace; anything else carries one on its WARN, as it may be a bug.
 */
internal class TelemetryOutageLog(private val leg: String, private val log: Logger) {
    private val failing = AtomicBoolean(false)

    /** An export reached the support service. */
    fun succeeded() {
        if (failing.compareAndSet(true, false)) log.info("Support telemetry exports reach the support service again leg={}", leg)
    }

    /** An export failed for [failure] (null when the exporter gave no reason). */
    fun failed(failure: Throwable?) {
        val reason = describe(failure)
        if (failing.compareAndSet(false, true)) {
            if (isExpected(failure)) {
                log.warn("Support telemetry exports to the support service fail; they are dropped until they succeed again leg={} reason=\"{}\" dropped={}", leg, reason, leg)
            } else {
                log.warn("Support telemetry exports to the support service fail; they are dropped until they succeed again leg={} reason=\"{}\" dropped={}", leg, reason, leg, failure)
            }
        } else {
            log.debug("Support telemetry export failed again leg={} reason=\"{}\"", leg, reason)
        }
    }

    /** An export the support service answered, but refused for this token or scope; the connection handles it. */
    fun refused(reason: String) {
        log.debug("Support telemetry export refused leg={} reason=\"{}\"", leg, reason)
    }

    companion object {
        /**
         * The HTTP status of a failed export, or null when it got no answer: from the OpenTelemetry
         * log exporter's failure, or the Micrometer metrics sender's ("Server responded with HTTP
         * status code 503 and body …").
         */
        fun statusOf(failure: Throwable?): Int? = causes(failure).firstNotNullOfOrNull { t ->
            (t as? FailedExportException.HttpExportException)?.takeIf { it.failedWithResponse() }?.response?.statusCode
                ?: MICROMETER_STATUS.find(t.message.orEmpty())?.groupValues?.get(1)?.toInt()
        }

        /** No answer, a failure to reach or read the service, or an HTTP answer: not a bug of the suite's. */
        fun isExpected(failure: Throwable?): Boolean = failure == null || statusOf(failure) != null || causes(failure).any { it is IOException }

        fun describe(failure: Throwable?): String {
            if (failure == null) return "no reason given"
            statusOf(failure)?.let { return "HTTP $it" }
            val io = causes(failure).firstOrNull { it is IOException } ?: return failure.toString()
            return listOfNotNull(io.javaClass.simpleName, io.message).joinToString(": ")
        }

        private fun causes(failure: Throwable?): Sequence<Throwable> = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSES)

        private val MICROMETER_STATUS = Regex("""HTTP status code (\d{3})""")
        private const val MAX_CAUSES = 8
    }
}

/**
 * The threads the logs leg's OTLP exporter answers on, and the quieting of the OpenTelemetry
 * exporter's own logging on them. That exporter logs every failed export, at WARNING with the HTTP
 * status or at SEVERE (ERROR in the app's log) with a stack trace; for this optional, lossy leg
 * [TelemetryOutageLog] logs instead. Only what is logged on these threads is dropped: the same
 * exporter class serves an installation's own OpenTelemetry export, whose failures stay in its log.
 */
internal object SupportTelemetryThreads {
    private val marked: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /**
     * The JUL loggers of the OTLP/HTTP exporter (the class moved in OpenTelemetry 1.63; both names
     * are covered). Held here, so the filter stays on the logger instances the exporter uses.
     */
    private val exporterLoggers: List<java.util.logging.Logger> = listOf(
        "io.opentelemetry.exporter.internal.http.HttpExporter",
        "io.opentelemetry.exporter.otlp.internal.HttpExporter",
    ).map { java.util.logging.Logger.getLogger(it) }

    init {
        exporterLoggers.forEach { logger ->
            val previous = logger.filter
            if (previous !is QuietOnTelemetryThreads) logger.filter = QuietOnTelemetryThreads(previous)
        }
    }

    private val threads: ThreadFactory = Thread.ofPlatform().daemon().name("support-telemetry-otlp-", 0).factory()

    /**
     * An executor for the exporter's HTTP client, whose threads are marked. Its concurrency is
     * bounded by the client's dispatcher (five requests per host), and the batch processor sends one
     * batch at a time.
     */
    fun newExecutor(): ExecutorService = Executors.newCachedThreadPool { work ->
        threads.newThread {
            marked.set(true)
            work.run()
        }
    }

    fun isTelemetryThread(): Boolean = marked.get()

    /** Drops INFO and above on the telemetry threads; keeps every record on any other thread. */
    private class QuietOnTelemetryThreads(private val previous: Filter?) : Filter {
        override fun isLoggable(record: LogRecord): Boolean = !(isTelemetryThread() && record.level.intValue() >= Level.INFO.intValue()) && (previous?.isLoggable(record) ?: true)
    }
}

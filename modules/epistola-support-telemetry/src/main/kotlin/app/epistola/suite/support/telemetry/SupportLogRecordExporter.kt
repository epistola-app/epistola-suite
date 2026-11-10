// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.exporter.otlp.http.logs.OtlpHttpLogRecordExporter
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.logs.data.LogRecordData
import io.opentelemetry.sdk.logs.export.LogRecordExporter
import java.time.Duration

/**
 * Exports log records to one support connection's `POST {otlp}/v1/logs` (OTLP/HTTP, protobuf, gzip),
 * deciding per batch:
 *
 * - without the `telemetry` scope, the batch is **dropped** and reported as exported, so the batch
 *   processor's bounded queue drains instead of filling while the installation is pending or
 *   after the scope was withdrawn;
 * - until this node knows the OTLP endpoint and holds a token, likewise dropped;
 * - a `403 insufficient_scope` answer tells the connection Epistola withdrew the scope, so the
 *   next batches are dropped until a check finds it granted again;
 * - a `401` tells the connection the service no longer accepts the token, which it replaces, and a
 *   batch the service takes that it accepts it, which ends the connection's back-off after a `401`;
 * - any other failure is an outage, logged once by [TelemetryOutageLog] — the exporter's own
 *   logging is quiet on its threads ([SupportTelemetryThreads]), as it would log every batch;
 * - otherwise sent through an [OtlpHttpLogRecordExporter] for the current endpoint, built on
 *   first use and rebuilt when the endpoint changes, whose `Authorization` header is read from
 *   the connection's cached token on every request — so a rotated token is used at once.
 */
internal class SupportLogRecordExporter(
    private val target: SupportOtlpTarget,
    private val timeout: Duration,
    private val connectTimeout: Duration,
) : LogRecordExporter {
    private class Current(val url: String, val exporter: OtlpHttpLogRecordExporter)

    @Volatile private var current: Current? = null

    @Volatile private var shutdown = false

    private val lock = Any()

    private val outage = target.logsOutage

    /** The HTTP client's threads, on which the exporter's own failure logging is quiet. */
    private val executor = SupportTelemetryThreads.newExecutor()

    override fun export(logs: Collection<LogRecordData>): CompletableResultCode {
        if (shutdown || !target.granted()) return CompletableResultCode.ofSuccess()
        val url = target.url(SupportOtlpTarget.LOGS) ?: return CompletableResultCode.ofSuccess()
        val token = target.token() ?: return CompletableResultCode.ofSuccess()
        val result = exporterFor(url).export(logs)
        result.whenComplete { if (result.isSuccess) target.exportSucceeded(token, outage) else target.exportFailed(result.failureThrowable, token, outage) }
        return result
    }

    override fun flush(): CompletableResultCode = current?.exporter?.flush() ?: CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = synchronized(lock) {
        shutdown = true
        val result = current?.exporter?.shutdown() ?: CompletableResultCode.ofSuccess()
        current = null
        // Not shut down by the exporter, as it was handed in: an unfinished call ends with it.
        executor.shutdownNow()
        result
    }

    private fun exporterFor(url: String): OtlpHttpLogRecordExporter {
        current?.takeIf { it.url == url }?.let { return it.exporter }
        return synchronized(lock) {
            current?.takeIf { it.url == url }?.exporter ?: build(url).also { built ->
                current?.exporter?.shutdown()
                current = Current(url, built)
            }
        }
    }

    private fun build(url: String): OtlpHttpLogRecordExporter = OtlpHttpLogRecordExporter
        .builder()
        .setEndpoint(url)
        .setHeaders(target::headers)
        .setCompression("gzip")
        .setTimeout(timeout)
        .setConnectTimeout(connectTimeout)
        // One attempt: telemetry is lossy by design, and retries with back-off would only hold up
        // the batch thread and a shutdown while the support service is down.
        .setRetryPolicy(null)
        .setExecutorService(executor)
        // The exporter's own metrics would otherwise go to GlobalOpenTelemetry, which this leg
        // must neither read nor initialise.
        .setMeterProvider(MeterProvider.noop())
        .build()
}

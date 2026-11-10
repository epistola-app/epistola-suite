// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.connection.SupportScope
import app.epistola.suite.support.privacy.SupportPrivacy
import io.opentelemetry.exporter.internal.FailedExportException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Where and whether telemetry goes over one support connection (ADR 0053 §1: telemetry is a service
 * every connection may grant), asked of the [SupportConnection] at the moment of each export. Nothing
 * here blocks on the network or throws: the connection's status is cached, the token is the one this
 * node already holds, and a failure reads as "not now".
 *
 * @property logPrivacy how a log line is turned into a record for this connection; read on the
 *   logging thread, so never from the database. The strictest settings by default.
 * @property metricsRewrite rewrites a metrics export for this connection's privacy; unchanged by default.
 */
class SupportOtlpTarget internal constructor(
    private val connection: SupportConnection,
    val logPrivacy: () -> TelemetryPrivacy = { TelemetryPrivacy.of(SupportPrivacy.STRICTEST, null) },
    val metricsRewrite: (ByteArray) -> ByteArray = { it },
) {
    /** The connection this target sends to. */
    val connectionId: UUID get() = connection.id

    /** Outages of each signal, logged once per outage and connection. */
    internal val logsOutage = TelemetryOutageLog("logs", LoggerFactory.getLogger(SupportTelemetryLogs::class.java))
    internal val metricsOutage = TelemetryOutageLog("metrics", LoggerFactory.getLogger(SupportTelemetryMeterRegistry::class.java))

    /** This target with [rewrite] applied to a metrics export before its own rewrite. */
    internal fun rewrittenFirst(rewrite: (ByteArray) -> ByteArray): SupportOtlpTarget = SupportOtlpTarget(connection, logPrivacy) { metricsRewrite(rewrite(it)) }

    /** True while the connection is selected for `telemetry`: what it asks for, granted or not. Cached. */
    fun selected(): Boolean = runCatching { connection.isSelected(SupportScope.TELEMETRY) }.getOrDefault(false)

    /** True while the connection grants this installation the `telemetry` scope. */
    fun granted(): Boolean = runCatching { connection.hasScope(SupportScope.TELEMETRY) }.getOrDefault(false)

    /**
     * The support service answered an export with `403 insufficient_scope`: Epistola withdrew
     * `telemetry`. [granted] reads false on this node until a check finds it granted again.
     */
    fun scopeRefused() {
        runCatching { connection.scopeRefused(SupportScope.TELEMETRY) }
    }

    /** `{epistola_otlp_endpoint}/v1/{signal}`; null until this node has read the service metadata. */
    fun url(signal: String): String? = runCatching { connection.otlpEndpoint() }.getOrNull()?.let { "${it.toString().trimEnd('/')}/v1/$signal" }

    /** The token this node holds; null when it holds none. */
    fun token(): String? = runCatching { connection.cachedAccessToken() }.getOrNull()

    /** `Authorization: Bearer <token>` with the token this node holds; empty when it holds none. */
    fun headers(): Map<String, String> = token()?.let(::bearer) ?: emptyMap()

    /**
     * An export sent with [token] succeeded: the service accepts it, which ends the connection's
     * back-off after a `401` (exports do not go through its REST client). Never throws.
     */
    internal fun exportSucceeded(token: String, outage: TelemetryOutageLog) {
        outage.succeeded()
        runCatching { connection.tokenAccepted(token) }
    }

    /**
     * What an export sent with [token] that failed for [failure] means: `403 insufficient_scope` is a
     * withdrawal ([scopeRefused]); `401` is a token the service no longer accepts, which the
     * connection replaces; anything else is an outage for [outage] to log. Never throws.
     */
    internal fun exportFailed(failure: Throwable?, token: String, outage: TelemetryOutageLog) {
        runCatching {
            when {
                isInsufficientScope(failure) -> {
                    outage.refused("403 insufficient_scope")
                    scopeRefused()
                }

                TelemetryOutageLog.statusOf(failure) == UNAUTHORIZED -> {
                    outage.refused("401: the token is no longer accepted")
                    connection.tokenRejected(token)
                }

                else -> outage.failed(failure)
            }
        }
    }

    companion object {
        /**
         * True when an export failed because the support service refused the `telemetry` scope:
         * HTTP 403 whose body names `insufficient_scope` (`docs/support-protocol.md`). Reads the
         * OpenTelemetry log exporter's failure and the Micrometer metrics sender's exception.
         */
        fun isInsufficientScope(failure: Throwable?): Boolean = generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(8).any { t ->
            val response = (t as? FailedExportException.HttpExportException)?.takeIf { it.failedWithResponse() }?.response
            if (response != null) {
                response.statusCode == 403 && String(response.responseBody ?: ByteArray(0), Charsets.UTF_8).contains(INSUFFICIENT_SCOPE)
            } else {
                // Micrometer: "Server responded with HTTP status code 403 and body …".
                t.message.orEmpty().let { "status code 403" in it && INSUFFICIENT_SCOPE in it }
            }
        }

        private const val INSUFFICIENT_SCOPE = "insufficient_scope"
        private const val UNAUTHORIZED = 401

        fun bearer(token: String): Map<String, String> = mapOf(AUTHORIZATION to "Bearer $token")

        const val AUTHORIZATION = "Authorization"
        const val LOGS = "logs"
        const val METRICS = "metrics"
    }
}

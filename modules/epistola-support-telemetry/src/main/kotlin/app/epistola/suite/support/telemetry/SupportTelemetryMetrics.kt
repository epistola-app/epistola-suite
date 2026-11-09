// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.ipc.http.HttpUrlConnectionSender
import io.micrometer.registry.otlp.AggregationTemporality
import io.micrometer.registry.otlp.CompressionMode
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpHttpMetricsSender
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.micrometer.registry.otlp.OtlpMetricsSender
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * The telemetry **metrics** leg: every application meter, pushed every `metric-step` to the
 * support service's `POST {otlp}/v1/metrics` (OTLP/HTTP, protobuf, gzip, delta temporality) while
 * Epistola grants `telemetry` (`docs/support-protocol.md`).
 *
 * It is a [CompositeMeterRegistry] around a private [OtlpMeterRegistry] on purpose. Spring Boot
 * composes every `MeterRegistry` bean into its primary composite, so the app's meters reach this
 * one; but a bean of type `OtlpMeterRegistry`, `OtlpMetricsSender` or `OtlpConfig` would switch off
 * Boot's own `management.otlp.metrics` export, which belongs to the customer. Exposing only this
 * wrapper keeps the two independent.
 *
 * The `tenant` tag is stripped on the inner OTLP registry when `strip-tenant-tag-from-metrics` is
 * on, and the `template` tag always. It has to be there: a composite nested in another registers each meter straight into the
 * leaf registries below it, so a filter on this wrapper would be skipped for every meter that
 * arrives through Boot's composite. For the same reason Boot's own meter filters and
 * `management.metrics.*` settings do not reach this leg; the identity it reports is the resource
 * ([TelemetryResource]), not tags.
 */
class SupportTelemetryMeterRegistry internal constructor(
    internal val otlp: OtlpMeterRegistry,
    stripTenantTag: Boolean,
    private val sender: ScopeGatedOtlpMetricsSender? = null,
) : CompositeMeterRegistry(Clock.SYSTEM, listOf(otlp)) {
    init {
        if (stripTenantTag) otlp.config().meterFilter(MeterFilter.ignoreTags(TENANT_TAG))
        // Never sent: a template id is the tenant's content. Dropped here, before aggregation, so the
        // series of different templates add up instead of arriving as points with equal attributes.
        otlp.config().meterFilter(MeterFilter.ignoreTags(TEMPLATE_TAG))
    }

    /**
     * Closes without the final publish Micrometer makes on close (telemetry is lossy by design, and
     * the support service must never hold up a shutdown), and waits at most [CLOSE_WAIT] for a
     * publish already under way: Spring closes every meter registry before the web server drains,
     * so a hanging support service would otherwise eat the pod's grace period.
     */
    override fun close() {
        sender?.close()
        // Detached first: closing a composite closes its registries on this thread, and closing the OTLP
        // registry waits for a publish under way, which could hang on the support service.
        remove(otlp)
        super.close()
        val closing = Thread.ofPlatform().daemon().name("support-telemetry-close").start { otlp.close() }
        closing.join(CLOSE_WAIT)
    }

    companion object {
        const val TENANT_TAG = "tenant"
        const val TEMPLATE_TAG = "template"

        /** The most closing waits for a publish to the support service already under way. */
        val CLOSE_WAIT: Duration = Duration.ofSeconds(1)

        internal fun create(
            properties: TelemetryProperties,
            target: SupportOtlpTarget,
            resource: TelemetryResource,
            connectTimeout: Duration,
            clock: Clock = Clock.SYSTEM,
            rewrite: (ByteArray) -> ByteArray = { it },
        ): SupportTelemetryMeterRegistry {
            val sender = ScopeGatedOtlpMetricsSender(target, rewrite, OtlpHttpMetricsSender(HttpUrlConnectionSender(connectTimeout, properties.exportTimeout)))
            val otlp = OtlpMeterRegistry.builder(SupportOtlpConfig(properties.metricStep, target, resource))
                .clock(clock)
                .metricsSender(sender)
                .build()
            return SupportTelemetryMeterRegistry(otlp, properties.stripTenantTagFromMetrics, sender)
        }
    }
}

/**
 * The OTLP settings of the support leg. Micrometer reads the URL and the headers on every
 * publish, so they follow the connection's endpoint and bearer token; it reads the resource
 * attributes — the suite's own [TelemetryResource] — once, when the registry is built.
 * Nothing falls through to Micrometer's defaults that read `OTEL_EXPORTER_OTLP_*` or
 * `OTEL_RESOURCE_ATTRIBUTES`, which configure the customer's observability, not this leg.
 */
internal class SupportOtlpConfig(
    private val step: Duration,
    private val target: SupportOtlpTarget,
    private val resource: TelemetryResource,
) : OtlpConfig {
    override fun get(key: String): String? = null

    /** A placeholder until the endpoint is known; [ScopeGatedOtlpMetricsSender] never sends to it. */
    override fun url(): String = target.url(SupportOtlpTarget.METRICS) ?: PLACEHOLDER_URL

    override fun headers(): Map<String, String> = target.headers()

    override fun resourceAttributes(): Map<String, String> = resource.attributes()

    override fun aggregationTemporality(): AggregationTemporality = AggregationTemporality.DELTA

    override fun compressionMode(): CompressionMode = CompressionMode.GZIP

    override fun step(): Duration = step

    companion object {
        const val PLACEHOLDER_URL = "http://localhost/v1/metrics"
    }
}

/**
 * Sends a publish only while Epistola grants `telemetry` and this node knows the endpoint and
 * holds a token, and then to the endpoint and with the token current **now** — not the ones the
 * registry read when it started the publish. Otherwise the step's data is dropped: with delta
 * temporality nothing carries over, so nothing builds up while the installation waits. A
 * `403 insufficient_scope` answer tells the connection Epistola withdrew the scope, a `401` that the
 * service no longer accepts the token. A failed publish is dropped, not thrown: Micrometer would log
 * every one at WARN with a stack trace; [TelemetryOutageLog] logs an outage once instead.
 */
internal class ScopeGatedOtlpMetricsSender(
    private val target: SupportOtlpTarget,
    /** Rewrites the export for the privacy settings in force (ADR 0052 §1–2); unchanged by default. */
    private val rewrite: (ByteArray) -> ByteArray = { it },
    private val delegate: OtlpMetricsSender,
) : OtlpMetricsSender {
    @Volatile private var closed = false

    private val outage = TelemetryOutageLog("metrics", LoggerFactory.getLogger(SupportTelemetryMeterRegistry::class.java))

    /** From now on every publish is dropped: the registry is closing. */
    fun close() {
        closed = true
    }

    override fun send(request: OtlpMetricsSender.Request) {
        if (closed || !target.granted()) return
        val url = target.url(SupportOtlpTarget.METRICS) ?: return
        val token = target.token() ?: return
        try {
            delegate.send(
                OtlpMetricsSender.Request.builder(rewrite(request.metricsData))
                    .address(url)
                    .headers(SupportOtlpTarget.bearer(token))
                    .compressionMode(request.compressionMode)
                    .build(),
            )
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            target.exportFailed(e, token, outage)
            return
        }
        target.exportSucceeded(token, outage)
    }
}

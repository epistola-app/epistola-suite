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
import org.springframework.scheduling.annotation.Scheduled
import java.time.Duration
import java.util.concurrent.TimeUnit

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
    private val targets: SupportTelemetryTargets? = null,
) : CompositeMeterRegistry(Clock.SYSTEM, if (targets == null) listOf(otlp) else emptyList()) {
    @Volatile private var attached = targets == null

    init {
        if (stripTenantTag) otlp.config().meterFilter(MeterFilter.ignoreTags(TENANT_TAG))
        // Never sent: a template id is the tenant's content. Dropped here, before aggregation, so the
        // series of different templates add up instead of arriving as points with equal attributes.
        otlp.config().meterFilter(MeterFilter.ignoreTags(TEMPLATE_TAG))
        refresh()
    }

    /**
     * Holds the OTLP registry only while a connection is selected for telemetry: an installation that
     * shares telemetry with nobody aggregates nothing for it. Meters registered before are carried
     * into it when it is added again. Every ten seconds, and when built.
     */
    @Scheduled(fixedDelay = REFRESH_SECONDS, timeUnit = TimeUnit.SECONDS)
    @Synchronized
    fun refresh() {
        val wanted = targets?.let { t -> runCatching { t.targets().isNotEmpty() }.getOrDefault(attached) } ?: return
        if (wanted == attached) return
        if (wanted) add(otlp) else remove(otlp)
        attached = wanted
    }

    /**
     * Closes without the final publish Micrometer makes on close (telemetry is lossy by design, and
     * the support service must never hold up a shutdown), and waits at most [CLOSE_WAIT] for a
     * publish already under way: Spring closes every meter registry before the web server drains,
     * so a hanging support service would otherwise eat the pod's grace period.
     */
    override fun close() {
        sender?.close()
        attached = false
        // Detached first: closing a composite closes its registries on this thread, and closing the OTLP
        // registry waits for a publish under way, which could hang on the support service.
        remove(otlp)
        super.close()
        val closing = Thread.ofPlatform().daemon().name("support-telemetry-close").start { otlp.close() }
        closing.join(CLOSE_WAIT)
    }

    companion object {
        const val TENANT_TAG = "tenant"
        private const val REFRESH_SECONDS = 10L
        const val TEMPLATE_TAG = "template"

        /** The most closing waits for a publish to the support service already under way. */
        val CLOSE_WAIT: Duration = Duration.ofSeconds(1)

        /** One connection, always held: the registry of the tests. */
        internal fun create(
            properties: TelemetryProperties,
            target: SupportOtlpTarget,
            resource: TelemetryResource,
            connectTimeout: Duration,
            clock: Clock = Clock.SYSTEM,
        ): SupportTelemetryMeterRegistry {
            val sender = ScopeGatedOtlpMetricsSender({ listOf(target) }, OtlpHttpMetricsSender(HttpUrlConnectionSender(connectTimeout, properties.exportTimeout)))
            val otlp = OtlpMeterRegistry.builder(SupportOtlpConfig(properties.metricStep, resource))
                .clock(clock)
                .metricsSender(sender)
                .build()
            return SupportTelemetryMeterRegistry(otlp, properties.stripTenantTagFromMetrics, sender)
        }

        /** Every connection selected for telemetry, each with its own privacy ([SupportTelemetryTargets]). */
        fun create(
            properties: TelemetryProperties,
            targets: SupportTelemetryTargets,
            resource: TelemetryResource,
            connectTimeout: Duration,
        ): SupportTelemetryMeterRegistry {
            val sender = ScopeGatedOtlpMetricsSender(targets::targets, OtlpHttpMetricsSender(HttpUrlConnectionSender(connectTimeout, properties.exportTimeout)))
            val otlp = OtlpMeterRegistry.builder(SupportOtlpConfig(properties.metricStep, resource))
                .metricsSender(sender)
                .build()
            return SupportTelemetryMeterRegistry(otlp, properties.stripTenantTagFromMetrics, sender, targets)
        }
    }
}

/**
 * The OTLP settings of the support leg. The address and the headers are a placeholder: the sender
 * ([ScopeGatedOtlpMetricsSender]) sends each publish to every connection that takes telemetry, with
 * that connection's endpoint and token. Micrometer reads the resource attributes — the suite's own
 * [TelemetryResource] — once, when the registry is built. Nothing falls through to Micrometer's
 * defaults that read `OTEL_EXPORTER_OTLP_*` or `OTEL_RESOURCE_ATTRIBUTES`, which configure the
 * customer's observability, not this leg.
 */
internal class SupportOtlpConfig(
    private val step: Duration,
    private val resource: TelemetryResource,
) : OtlpConfig {
    override fun get(key: String): String? = null

    /** Never sent to: [ScopeGatedOtlpMetricsSender] addresses each connection itself. */
    override fun url(): String = PLACEHOLDER_URL

    override fun headers(): Map<String, String> = emptyMap()

    override fun resourceAttributes(): Map<String, String> = resource.attributes()

    override fun aggregationTemporality(): AggregationTemporality = AggregationTemporality.DELTA

    override fun compressionMode(): CompressionMode = CompressionMode.GZIP

    override fun step(): Duration = step

    companion object {
        const val PLACEHOLDER_URL = "http://localhost/v1/metrics"
    }
}

/**
 * Sends each publish to every connection selected for telemetry that grants it, and to which this
 * node knows the endpoint and holds a token, then with the endpoint and token current **now**, and
 * rewritten for that connection's privacy (ADR 0052 §1–2). Otherwise that connection's copy of the
 * step's data is dropped: with delta temporality nothing carries over, so nothing builds up while it
 * waits. A `403 insufficient_scope` answer tells the connection it withdrew the scope, a `401` that it
 * no longer accepts the token. A failed send is dropped, not thrown, and one connection's failure does
 * not hold back the others: Micrometer would log every one at WARN with a stack trace;
 * [TelemetryOutageLog] logs an outage once per connection instead.
 */
internal class ScopeGatedOtlpMetricsSender(
    private val targets: () -> List<SupportOtlpTarget>,
    private val delegate: OtlpMetricsSender,
) : OtlpMetricsSender {
    /** One connection: [target], its export rewritten by [rewrite] before its own rewrite. */
    constructor(target: SupportOtlpTarget, rewrite: (ByteArray) -> ByteArray = { it }, delegate: OtlpMetricsSender) :
        this(target.rewrittenFirst(rewrite).let { t -> { listOf(t) } }, delegate)

    @Volatile private var closed = false

    /** From now on every publish is dropped: the registry is closing. */
    fun close() {
        closed = true
    }

    override fun send(request: OtlpMetricsSender.Request) {
        if (closed) return
        val current = runCatching { targets() }.getOrDefault(emptyList())
        current.forEach { send(it, request) }
    }

    private fun send(target: SupportOtlpTarget, request: OtlpMetricsSender.Request) {
        if (!target.granted()) return
        val url = target.url(SupportOtlpTarget.METRICS) ?: return
        val token = target.token() ?: return
        try {
            delegate.send(
                OtlpMetricsSender.Request.builder(target.metricsRewrite(request.metricsData))
                    .address(url)
                    .headers(SupportOtlpTarget.bearer(token))
                    .compressionMode(request.compressionMode)
                    .build(),
            )
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            target.exportFailed(e, token, target.metricsOutage)
            return
        }
        target.exportSucceeded(token, target.metricsOutage)
    }
}

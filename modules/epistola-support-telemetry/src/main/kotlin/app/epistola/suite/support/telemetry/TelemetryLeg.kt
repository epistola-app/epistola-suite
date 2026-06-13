package app.epistola.suite.support.telemetry

import app.epistola.hub.client.port.InstallationStore
import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.support.HubTelemetryEndpointResolver
import app.epistola.suite.support.SupportEntitlementService
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import io.opentelemetry.exporter.otlp.logs.OtlpGrpcLogRecordExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.logs.SdkLoggerProvider
import io.opentelemetry.sdk.logs.export.BatchLogRecordProcessor
import io.opentelemetry.sdk.resources.Resource
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.info.BuildProperties
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration

/**
 * The dedicated, isolated OTLP telemetry leg (ADR 0006): forwards application logs (a Logback
 * appender → OTLP-over-gRPC) and metrics (a dedicated Micrometer OTLP registry whose sender ships the
 * payload over OTLP-over-gRPC) to the Hub. Everything speaks OTLP over gRPC on the same endpoint/port
 * the Suite already resolves for the Hub. Bean only exists when **both** `epistola.support.enabled=true` and
 * `epistola.support.telemetry.enabled=true`; on top of that, it activates only when the installation
 * holds an installation-wide `support-telemetry` entitlement.
 *
 * The global grant + per-tenant DENY opt-out are snapshotted at activation; a change to the
 * installation-wide grant takes effect on the next restart (the operator's `enabled` switch is a
 * restart-level switch anyway), while per-tenant exclusions are applied per log record. Metrics carry
 * no per-tenant dimension; the `tenant` tag is stripped before forwarding by default.
 */
@Component
@EnableConfigurationProperties(TelemetryProperties::class)
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled", "telemetry.enabled"], havingValue = "true")
class TelemetryLeg(
    private val props: TelemetryProperties,
    private val installationStore: InstallationStore,
    private val entitlement: SupportEntitlementService,
    private val endpointResolver: HubTelemetryEndpointResolver,
    private val meterRegistry: MeterRegistry,
    private val nodeIdentity: NodeIdentity,
    private val installationProperties: InstallationProperties,
    private val buildProperties: BuildProperties? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var openTelemetry: OpenTelemetrySdk? = null
    private var logAppender: TelemetryLogAppender? = null
    private var otlpMeterRegistry: OtlpMeterRegistry? = null
    private var metricsChannel: ManagedChannel? = null

    /**
     * Activate after the rest of the context is ready (and, ordered last, after the support module's
     * initial entitlement fetch) so the installation-wide grant is observed when present.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun start() {
        if (!entitlement.isInstallationEntitled(TELEMETRY_FEATURE)) {
            log.info("Telemetry leg enabled but installation is not entitled (support-telemetry); not forwarding")
            return
        }
        val credentials = installationStore.load()
        if (credentials == null) {
            log.warn("Telemetry leg enabled and entitled, but no hub credentials yet; not forwarding")
            return
        }
        val endpoint =
            try {
                endpointResolver.resolve()
            } catch (e: Exception) {
                log.warn("Telemetry leg could not resolve the hub OTLP endpoint; not forwarding: {}", e.message)
                return
            }

        val resource = buildResource(credentials.installationId.toString())
        val deniedTenants = entitlement.deniedTenants(TELEMETRY_FEATURE)

        if (props.logs) {
            startLogForwarding(endpoint, resource, credentials.apiKey, deniedTenants)
        }
        if (props.metrics) {
            startMetricForwarding(endpoint, credentials.apiKey)
        }
        log.info(
            "Telemetry leg active → {} (logs={}, metrics={}, deniedTenants={})",
            endpoint,
            props.logs,
            props.metrics,
            deniedTenants.size,
        )
    }

    @PreDestroy
    fun stop() {
        logAppender?.let { detachAppender(it) }
        logAppender = null
        openTelemetry?.close()
        openTelemetry = null
        otlpMeterRegistry?.let {
            (meterRegistry as? CompositeMeterRegistry)?.remove(it)
            it.close()
        }
        otlpMeterRegistry = null
        metricsChannel?.shutdown()
        metricsChannel = null
    }

    private fun startLogForwarding(
        endpoint: String,
        resource: Resource,
        apiKey: String,
        deniedTenants: Set<String>,
    ) {
        val exporter =
            OtlpGrpcLogRecordExporter
                .builder()
                .setEndpoint(endpoint)
                .addHeader(API_KEY_HEADER, apiKey)
                .setCompression("gzip")
                .setTimeout(props.exportTimeout)
                .build()
        val processor =
            BatchLogRecordProcessor
                .builder(exporter)
                .setMaxQueueSize(props.logMaxQueueSize)
                .setMaxExportBatchSize(props.logMaxBatchSize)
                .setScheduleDelay(props.logScheduleDelay)
                .build()
        val sdk =
            OpenTelemetrySdk
                .builder()
                .setLoggerProvider(
                    SdkLoggerProvider
                        .builder()
                        .setResource(resource)
                        .addLogRecordProcessor(processor)
                        .build(),
                ).build()
        openTelemetry = sdk

        val otelLogger = sdk.logsBridge.get("app.epistola.suite")
        val appender =
            TelemetryLogAppender(otelLogger) { tenantKey ->
                // Installation-wide grant already checked at activation; only per-tenant DENY varies.
                tenantKey == null || tenantKey !in deniedTenants
            }
        attachAppender(appender)
        logAppender = appender
    }

    private fun startMetricForwarding(
        endpoint: String,
        apiKey: String,
    ) {
        val channel = buildChannel(endpoint)
        val registry =
            OtlpMeterRegistry
                .builder(otlpMeterConfig(endpoint))
                .clock(Clock.SYSTEM)
                .metricsSender(GrpcOtlpMetricsSender(channel, apiKey))
                .build()
        if (props.stripTenantTagFromMetrics) {
            registry.config().meterFilter(MeterFilter.ignoreTags("tenant"))
        }
        (meterRegistry as? CompositeMeterRegistry)?.add(registry)
            ?: log.warn("Primary MeterRegistry is not composite; telemetry metrics leg not attached")
        metricsChannel = channel
        otlpMeterRegistry = registry
    }

    /** A gRPC channel to the OTLP endpoint (`http://host:port` → plaintext, `https` → TLS). */
    private fun buildChannel(endpoint: String): ManagedChannel {
        val uri = URI(endpoint)
        val plaintext = uri.scheme.equals("http", ignoreCase = true)
        val port = if (uri.port != -1) {
            uri.port
        } else if (plaintext) {
            80
        } else {
            443
        }
        val builder = ManagedChannelBuilder.forAddress(uri.host, port)
        if (plaintext) builder.usePlaintext()
        return builder.build()
    }

    private fun buildResource(installationId: String): Resource = Resource
        .getDefault()
        .toBuilder()
        .put("service.name", "epistola-suite")
        .put("service.instance.id", nodeIdentity.nodeId)
        .put("installation_id", installationId)
        .put("deployment.environment", installationProperties.environment.ifBlank { "unknown" })
        .put("service.version", buildProperties?.version ?: "dev")
        .build()

    private fun otlpMeterConfig(endpoint: String): OtlpConfig = object : OtlpConfig {
        override fun get(key: String): String? = null

        // Address only — the gRPC sender owns transport and auth; the registry does not POST here.
        override fun url(): String = endpoint

        override fun step(): Duration = props.metricStep
    }

    private fun attachAppender(appender: TelemetryLogAppender) {
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: run {
            log.warn("Logger factory is not Logback; telemetry log forwarding disabled")
            return
        }
        val threshold =
            ThresholdFilter().apply {
                setLevel(Level.toLevel(props.logLevel, Level.INFO).levelStr)
                start()
            }
        appender.apply {
            this.context = context
            name = "EPISTOLA_TELEMETRY_OTLP"
            addFilter(threshold)
            start()
        }
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)
    }

    private fun detachAppender(appender: TelemetryLogAppender) {
        (LoggerFactory.getILoggerFactory() as? LoggerContext)
            ?.getLogger(Logger.ROOT_LOGGER_NAME)
            ?.detachAppender(appender)
        appender.stop()
    }

    companion object {
        private val TELEMETRY_FEATURE = FeatureKey.of("support-telemetry")
        private const val API_KEY_HEADER = "x-ep-api-key"
    }
}

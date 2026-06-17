package app.epistola.suite.support.telemetry

import app.epistola.hub.client.port.InstallationStore
import app.epistola.hub.contract.HubHeaders
import app.epistola.hub.contract.SupportFeature
import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.support.HubTelemetryEndpointResolver
import app.epistola.suite.support.SupportEntitlementService
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
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

/**
 * The telemetry **logs** leg (ADR 0006): attaches a Logback appender that forwards application-log
 * events to the Hub over OTLP-over-gRPC, on the same endpoint/port the Suite already resolves for the
 * Hub. Bean only exists when **both** `epistola.support.enabled=true` and
 * `epistola.support.telemetry.enabled=true`; on top of that, it activates only when the installation
 * holds an installation-wide `support-telemetry` entitlement. Metrics are wired separately by
 * [TelemetryMetricsConfiguration].
 *
 * The global grant + per-tenant DENY opt-out are snapshotted at activation; a change to the
 * installation-wide grant takes effect on the next restart (the operator's `enabled` switch is a
 * restart-level switch anyway), while per-tenant exclusions are applied per log record.
 */
@Component
@EnableConfigurationProperties(TelemetryProperties::class)
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled", "telemetry.enabled"], havingValue = "true")
class TelemetryLeg(
    private val props: TelemetryProperties,
    private val installationStore: InstallationStore,
    private val entitlement: SupportEntitlementService,
    private val endpointResolver: HubTelemetryEndpointResolver,
    private val nodeIdentity: NodeIdentity,
    private val installationProperties: InstallationProperties,
    private val buildProperties: BuildProperties? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private var openTelemetry: OpenTelemetrySdk? = null
    private var logAppender: TelemetryLogAppender? = null

    /**
     * Activate after the rest of the context is ready (and, ordered last, after the support module's
     * initial entitlement fetch) so the installation-wide grant is observed when present.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun start() {
        if (!props.logs) return
        if (!entitlement.isInstallationEntitled(TELEMETRY_FEATURE)) {
            log.info("Telemetry logs leg enabled but installation is not entitled (support-telemetry); not forwarding")
            return
        }
        val credentials = installationStore.load()
        if (credentials == null) {
            log.warn("Telemetry logs leg enabled and entitled, but no hub credentials yet; not forwarding")
            return
        }
        val endpoint =
            try {
                endpointResolver.resolve()
            } catch (e: Exception) {
                log.warn("Telemetry logs leg could not resolve the hub OTLP endpoint; not forwarding: {}", e.message)
                return
            }

        val resource = buildResource(credentials.installationId.toString())
        val deniedTenants = entitlement.deniedTenants(TELEMETRY_FEATURE)
        startLogForwarding(endpoint, resource, credentials.apiKey, deniedTenants)
        log.info("Telemetry logs leg active → {} (deniedTenants={})", endpoint, deniedTenants.size)
    }

    @PreDestroy
    fun stop() {
        logAppender?.let { detachAppender(it) }
        logAppender = null
        openTelemetry?.close()
        openTelemetry = null
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

    private fun buildResource(installationId: String): Resource = Resource
        .getDefault()
        .toBuilder()
        .put("service.name", "epistola-suite")
        .put("service.instance.id", nodeIdentity.nodeId)
        .put("installation_id", installationId)
        .put("deployment.environment", installationProperties.environment.ifBlank { "unknown" })
        .put("service.version", buildProperties?.version ?: "dev")
        .build()

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
        private val TELEMETRY_FEATURE = FeatureKey.of(SupportFeature.TELEMETRY.wireKey)
        private const val API_KEY_HEADER = HubHeaders.API_KEY
    }
}

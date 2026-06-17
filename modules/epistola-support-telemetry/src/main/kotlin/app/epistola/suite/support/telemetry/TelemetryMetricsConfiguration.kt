package app.epistola.suite.support.telemetry

import app.epistola.hub.client.port.InstallationStore
import app.epistola.hub.contract.SupportFeature
import app.epistola.suite.common.ids.FeatureKey
import app.epistola.suite.support.HubTelemetryEndpointResolver
import app.epistola.suite.support.SupportEntitlementService
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.registry.otlp.OtlpConfig
import io.micrometer.registry.otlp.OtlpMeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Wires the telemetry **metrics** leg (ADR 0006) as a Spring [OtlpMeterRegistry] bean so Spring Boot
 * composes it with the other registries and fans every application meter to it — the robust way to
 * tee metrics, independent of whether a composite primary happens to exist. The registry's
 * `OtlpMetricsSender` ([GrpcOtlpMetricsSender]) ships the payload to the Hub over gRPC and owns the
 * gating (registered + entitled) per publish, so the bean is safe to exist from startup. The common
 * identity tags (`service`/`instance`/`installation_id`/…) are applied by Boot's `MeterFilter` beans
 * to this registry like any other; the per-tenant `tenant` tag is stripped here before forwarding.
 *
 * Logs are wired separately by [TelemetryLeg] (a Logback appender), which has no composite concern.
 */
@Configuration
@EnableConfigurationProperties(TelemetryProperties::class)
@ConditionalOnProperty(prefix = "epistola.support", name = ["enabled", "telemetry.enabled"], havingValue = "true")
class TelemetryMetricsConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "epistola.support.telemetry", name = ["metrics"], havingValue = "true", matchIfMissing = true)
    fun telemetryMetricsSender(
        endpointResolver: HubTelemetryEndpointResolver,
        installationStore: InstallationStore,
        entitlement: SupportEntitlementService,
    ): GrpcOtlpMetricsSender = GrpcOtlpMetricsSender(
        endpoint = endpointResolver::resolve,
        credentials = installationStore::load,
        entitled = { entitlement.isInstallationEntitled(TELEMETRY_FEATURE) },
    )

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "epistola.support.telemetry", name = ["metrics"], havingValue = "true", matchIfMissing = true)
    fun telemetryOtlpMeterRegistry(
        props: TelemetryProperties,
        sender: GrpcOtlpMetricsSender,
    ): OtlpMeterRegistry {
        val registry =
            OtlpMeterRegistry
                .builder(otlpConfig(props))
                .clock(Clock.SYSTEM)
                .metricsSender(sender)
                .build()
        if (props.stripTenantTagFromMetrics) {
            registry.config().meterFilter(MeterFilter.ignoreTags("tenant"))
        }
        return registry
    }

    /** Address is a placeholder — the gRPC sender owns transport and auth; only the step matters here. */
    private fun otlpConfig(props: TelemetryProperties): OtlpConfig = object : OtlpConfig {
        override fun get(key: String): String? = null

        override fun url(): String = "http://localhost"

        override fun step(): Duration = props.metricStep
    }

    private companion object {
        val TELEMETRY_FEATURE = FeatureKey.of(SupportFeature.TELEMETRY.wireKey)
    }
}

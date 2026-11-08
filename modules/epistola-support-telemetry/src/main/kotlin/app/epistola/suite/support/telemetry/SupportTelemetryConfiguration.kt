// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.support.SupportProperties
import app.epistola.suite.support.connection.SupportConnection
import app.epistola.suite.support.privacy.SupportPrivacy
import app.epistola.suite.support.privacy.TenantReferences
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn

/**
 * The telemetry leg to the support service, when the support connection and
 * `epistola.support.telemetry.enabled` are on. Whether anything is sent is decided per export by
 * the `telemetry` scope Epistola grants. The logs leg is [SupportTelemetryLogs].
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryProperties::class)
@ConditionalOnBooleanProperty("epistola.support.enabled")
@ConditionalOnBooleanProperty(name = ["epistola.support.telemetry.enabled"], matchIfMissing = true)
class SupportTelemetryConfiguration {
    /**
     * Reads the installation id from `app_metadata`, so it waits for Flyway: Micrometer's OTLP
     * registry reads the resource attributes once, when it is built.
     */
    @Bean
    @ConditionalOnMissingBean
    @DependsOn("flywayInitializer")
    fun supportTelemetryResource(
        nodeIdentity: NodeIdentity,
        installations: InstallationService,
        installation: InstallationProperties,
        buildProperties: ObjectProvider<BuildProperties>,
    ): TelemetryResource = TelemetryResource(
        nodeId = nodeIdentity.nodeId,
        environment = installation.environment.ifBlank { "unknown" },
        version = buildProperties.ifAvailable?.version ?: "dev",
        installationId = { installations.get().id.toString() },
    )

    /** See [SupportTelemetryMeterRegistry] for why this is the only metrics type exposed as a bean. */
    @Bean(destroyMethod = "close")
    @ConditionalOnBooleanProperty(name = ["epistola.support.telemetry.metrics"], matchIfMissing = true)
    fun supportTelemetryMeterRegistry(
        properties: TelemetryProperties,
        supportProperties: SupportProperties,
        connection: SupportConnection,
        resource: TelemetryResource,
        privacy: ObjectProvider<SupportPrivacy>,
        references: ObjectProvider<TenantReferences>,
    ): SupportTelemetryMeterRegistry = SupportTelemetryMeterRegistry.create(
        properties,
        SupportOtlpTarget(connection),
        resource,
        supportProperties.connectTimeout,
        rewrite = { data ->
            val settings = privacy.ifAvailable?.current()
            val refs = references.ifAvailable
            if (settings == null || refs == null) data else MetricsPrivacy.apply(data, TelemetryPrivacy(settings.pseudonymised, refs::reference), settings.confidential)
        },
    )
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.telemetry

import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.observability.NodeIdentity
import app.epistola.suite.support.SupportProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn

/**
 * The telemetry leg to the support connections, while `epistola.support.telemetry.enabled` is on.
 * Whether anything is sent, and where, is decided per export by each connection's `telemetry` grant
 * (ADR 0053 §1). The logs leg is [SupportTelemetryLogs].
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TelemetryProperties::class)
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
        targets: SupportTelemetryTargets,
        resource: TelemetryResource,
    ): SupportTelemetryMeterRegistry = SupportTelemetryMeterRegistry.create(
        properties,
        targets,
        resource,
        supportProperties.connectTimeout,
    )
}

package app.epistola.suite.config

import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.observability.NodeIdentity
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.config.MeterFilter
import org.slf4j.LoggerFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn

/**
 * Cross-cutting metric configuration for fleet monitoring.
 *
 * Stamps every meter with the identity tags needed to tell instances apart once
 * many remote instances report into one backend (and, later, push to
 * epistola-hub):
 *
 *   - `service`         — always `epistola-suite`
 *   - `instance`        — the per-process node id ([NodeIdentity])
 *   - `installation_id` — the stable installation UUID (shared across pods)
 *   - `environment`     — `epistola.installation.environment`
 *   - `version`         — the running build version
 *
 * These survive OTLP push as datapoint/resource attributes — the hub's
 * per-instance/per-installation partition key. Without them, metrics from N
 * instances are indistinguishable.
 *
 * Implemented as a [MeterFilter] bean (pure micrometer-core) which Spring Boot
 * applies to every registry — Prometheus scrape and OTLP push alike.
 */
@Configuration(proxyBeanMethods = false)
class MetricsConfig {

    /**
     * `installation_id` is read from `app_metadata`, so this bean must not be
     * created before Flyway has run — hence the explicit `@DependsOn`
     * (JDBI has no implicit depends-on like `JdbcOperations` does).
     */
    @Bean
    @DependsOn("flywayInitializer")
    fun commonMetricTags(
        nodeIdentity: NodeIdentity,
        installations: InstallationService,
        installationProperties: InstallationProperties,
        buildProperties: BuildProperties? = null,
    ): MeterFilter {
        val installationId = runCatching { installations.get().id.toString() }
            .getOrElse {
                logger.warn("Could not resolve installation id for metric tags; using \"unknown\"", it)
                "unknown"
            }
        return commonTagsFilter(
            nodeId = nodeIdentity.nodeId,
            installationId = installationId,
            environment = installationProperties.environment.ifBlank { "unknown" },
            version = buildProperties?.version ?: "dev",
        )
    }

    /** Pure tag-building, split out from bean wiring so it is unit-testable. */
    internal fun commonTagsFilter(
        nodeId: String,
        installationId: String,
        environment: String,
        version: String,
    ): MeterFilter {
        val tags = listOf(
            Tag.of("service", "epistola-suite"),
            Tag.of("instance", nodeId),
            Tag.of("installation_id", installationId),
            Tag.of("environment", environment),
            Tag.of("version", version),
        )
        logger.info("Tagging all metrics with: {}", tags.joinToString { "${it.key}=${it.value}" })
        return MeterFilter.commonTags(tags)
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(MetricsConfig::class.java)
    }
}

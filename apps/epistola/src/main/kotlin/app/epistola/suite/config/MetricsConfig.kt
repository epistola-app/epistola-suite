// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.installation.InstallationService
import app.epistola.suite.observability.NodeIdentity
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
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
     * Resolves the identity tag values **once**, at startup. This is correct, not
     * a limitation:
     *
     *  - Micrometer common tags are static by design — a metrics backend keys a
     *    time series by its tag set, so mutating `installation_id` mid-process
     *    would split one series into two (worse than a stable value).
     *  - `installation_id` is itself invariant: `InstallationService` seeds the
     *    `app_metadata` row exactly once via Flyway and it stays fixed for the
     *    lifetime of the database (every pod reads the same value). A genuine
     *    identity change (e.g. the database is swapped under a running pod) is
     *    picked up on the next pod restart, which is the right granularity.
     *
     * `installation_id` is read from `app_metadata`, so this bean must not be
     * created before Flyway has run — hence the explicit `@DependsOn`
     * (JDBI has no implicit depends-on like `JdbcOperations` does). The
     * `runCatching → "unknown"` fallback only guards a startup read failure;
     * it never crashes boot over a metric tag.
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

    /**
     * Strips the `instance` tag from installation-wide gauges
     * (`epistola.installation.*`). These are published by a single leader replica
     * (see `InstallationStatsPublisher`); keeping `instance` would make the
     * series churn by pod every time leadership moves. Without it they stay one
     * stable series keyed by `installation_id`.
     */
    @Bean
    fun stripInstanceFromInstallationGauges(): MeterFilter = object : MeterFilter {
        override fun map(id: Meter.Id): Meter.Id {
            if (!id.name.startsWith(INSTALLATION_METRIC_PREFIX)) return id
            return id.replaceTags(Tags.of(id.tags.filterNot { it.key == "instance" }))
        }
    }

    private companion object {
        const val INSTALLATION_METRIC_PREFIX = "epistola.installation."
        private val logger = LoggerFactory.getLogger(MetricsConfig::class.java)
    }
}

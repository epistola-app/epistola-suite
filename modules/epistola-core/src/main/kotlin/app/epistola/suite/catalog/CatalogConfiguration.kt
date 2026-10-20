// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.outbound.CatalogSourceProperties
import app.epistola.suite.catalog.outbound.OutboundHttpFetcher
import app.epistola.suite.catalog.outbound.OutboundSourceReader
import app.epistola.suite.catalog.outbound.OutboundUrlGuard
import app.epistola.suite.common.ids.TenantKey
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.util.unit.DataSize
import java.time.Duration

@Configuration
@EnableConfigurationProperties(CatalogSizeLimits::class, CatalogUpstreamCheckProperties::class, CatalogSourceProperties::class, CatalogIdentityProperties::class)
class CatalogConfiguration {

    /**
     * The one HTTP client for catalog and code-list sources. Deliberately not a `RestClient` bean:
     * anything that could inject a general-purpose client could also fetch around the guard.
     */
    @Bean
    fun outboundHttpFetcher(guard: OutboundUrlGuard): OutboundHttpFetcher = OutboundHttpFetcher(guard)

    @Bean
    fun outboundSourceReader(
        guard: OutboundUrlGuard,
        fetcher: OutboundHttpFetcher,
        resourceLoader: ResourceLoader,
        sizeLimits: CatalogSizeLimits,
    ): OutboundSourceReader = OutboundSourceReader(guard, fetcher, resourceLoader, sizeLimits.maxDecompressedSize.toBytes())
}

/**
 * Configurable size limits for catalog ZIP import and export.
 *
 * ```yaml
 * epistola:
 *   catalog:
 *     max-zip-size: 10MB
 *     max-decompressed-size: 20MB
 * ```
 */
@ConfigurationProperties(prefix = "epistola.catalog")
data class CatalogSizeLimits(
    val maxZipSize: DataSize = DataSize.ofMegabytes(10),
    val maxDecompressedSize: DataSize = DataSize.ofMegabytes(20),
)

/**
 * How this installation names catalogs (ADR 0003 in the contract repo).
 *
 * A catalog is identified by registry, namespace and key. [defaultRegistry] is the registry `~` stands
 * for, and the registry given to a catalog that arrives from before wire v8, which names none.
 *
 * ```yaml
 * epistola:
 *   catalog:
 *     identity:
 *       default-registry: exchange.epistola.app
 * ```
 */
@ConfigurationProperties(prefix = "epistola.catalog.identity")
data class CatalogIdentityProperties(
    val defaultRegistry: String = "exchange.epistola.app",
) {
    companion object {
        /** The namespace of the catalogs Epistola ships (system, demo) on the default registry. */
        const val BUNDLED_NAMESPACE = "epistola"

        /**
         * The namespace an authored catalog gets unless it is given one: its tenant's key, padded with
         * `0` to the length a namespace needs, as the contract pads a short key. The 2.0 migration
         * names existing catalogs the same way.
         */
        fun defaultNamespace(tenantKey: TenantKey): String = tenantKey.value.padEnd(CatalogNamespaces.MIN_LENGTH, '0')
    }
}

/**
 * How often a subscribed catalog's source is asked whether it has published anything newer.
 *
 * Two cadences, not one. [pollIntervalMs] is how often the cluster task looks for catalogs that are
 * *due*; [interval] is how often any single catalog is actually asked. Conflating them would make
 * the tick rate the request rate.
 *
 * ```yaml
 * epistola:
 *   catalog:
 *     upstream-check:
 *       enabled: true
 *       interval: 6h
 * ```
 */
@ConfigurationProperties(prefix = "epistola.catalog.upstream-check")
data class CatalogUpstreamCheckProperties(
    /**
     * Turns background checking off entirely.
     *
     * On by default: subscribing to a catalog is already a decision to talk to its source, and the
     * on-demand check has always made the same request. An installation that must not make
     * unattended outbound calls sets this false and keeps the button.
     */
    val enabled: Boolean = true,
    /** How often the task looks for due catalogs. Not the per-catalog rate. */
    val pollIntervalMs: Long = 60_000,
    /** How long a catalog's answer is treated as current. Jittered by ±15% when scheduling. */
    val interval: Duration = Duration.ofHours(6),
    /** How many catalogs one tick will check, so a large tenant spreads over several. */
    val batchSize: Int = 25,
    /** How long a claim is honoured before another node may take the catalog over. */
    val claimLease: Duration = Duration.ofMinutes(5),
)

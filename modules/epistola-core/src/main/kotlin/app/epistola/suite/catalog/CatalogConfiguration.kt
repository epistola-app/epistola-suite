// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.util.unit.DataSize
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
@EnableConfigurationProperties(CatalogSizeLimits::class, CatalogUpstreamCheckProperties::class)
class CatalogConfiguration {

    @Bean
    fun catalogRestClient(): RestClient = RestClient.builder()
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(15))
            },
        )
        .defaultHeader("Accept", "application/json")
        .build()
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

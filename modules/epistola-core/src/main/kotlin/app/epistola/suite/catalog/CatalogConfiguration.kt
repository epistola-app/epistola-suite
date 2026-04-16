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
@EnableConfigurationProperties(CatalogSizeLimits::class)
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

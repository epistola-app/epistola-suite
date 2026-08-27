// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import jakarta.annotation.PostConstruct
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.mapper.ColumnMapper
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Configuration
@EnableConfigurationProperties(ExchangeProperties::class)
class ExchangeConfiguration(
    private val jdbi: Jdbi,
) {
    @PostConstruct
    fun registerMappers() {
        jdbi.registerColumnMapper(
            ExchangeConnectionStatus::class.java,
            ColumnMapper { rs, column, _ -> ExchangeConnectionStatus.valueOf(rs.getString(column)) },
        )
        jdbi.registerColumnMapper(
            CatalogPublicationStatus::class.java,
            ColumnMapper { rs, column, _ -> CatalogPublicationStatus.valueOf(rs.getString(column)) },
        )
    }

    /**
     * Exchange is a remote third party reached from a request thread and from the cluster
     * publication worker, so every call is bounded — an unresponsive Exchange must never pin a
     * worker or a servlet thread.
     *
     * Built on the JDK HTTP client rather than `SimpleClientHttpRequestFactory` because OAuth
     * carries its meaning in the body of error responses (`invalid_client` vs `invalid_grant`),
     * and `HttpURLConnection` does not reliably expose the body of a 401.
     */
    @Bean
    fun exchangeRestClient(properties: ExchangeProperties): RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build(),
            ).apply { setReadTimeout(properties.readTimeout) },
        )
        .build()
}

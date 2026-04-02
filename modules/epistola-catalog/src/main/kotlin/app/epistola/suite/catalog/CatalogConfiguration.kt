package app.epistola.suite.catalog

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

@Configuration
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

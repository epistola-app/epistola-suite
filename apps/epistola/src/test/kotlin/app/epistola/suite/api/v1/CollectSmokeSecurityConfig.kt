package app.epistola.suite.api.v1

import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.api.security.ClientIdentityFilter
import app.epistola.suite.apikeys.ApiKeyService
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * Test-only security wiring for [CollectEndpointSmokeIT].
 *
 * The default `test` profile registers a single permit-all `SecurityFilterChain`
 * for the integration-test convenience of all the other suites. That chain
 * does NOT include `ApiKeyAuthenticationFilter` or `ClientIdentityFilter` —
 * exactly the surface the smoke test exists to exercise.
 *
 * This config registers an `/api` chain that mirrors the production one
 * (minus OAuth2/JWT, which the smoke test doesn't need), at `@Order(1)` so it
 * matches before the test profile's permit-all chain (default order, lowest
 * precedence). Imported only by the smoke IT, so other test classes see the
 * permit-all chain unchanged.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
class CollectSmokeSecurityConfig {

    @Bean
    @Order(1)
    fun smokeApiSecurityFilterChain(
        http: HttpSecurity,
        apiKeyService: ApiKeyService,
        meterRegistry: MeterRegistry,
    ): SecurityFilterChain {
        val apiKeyFilter = ApiKeyAuthenticationFilter(apiKeyService, meterRegistry)
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests {
                // /api/ping is dual-mode per the contract — anonymous probes get a
                // basic pong; the controller decides the payload based on principal.
                it.requestMatchers(HttpMethod.POST, "/api/ping").permitAll()
                it.anyRequest().authenticated()
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            .addFilterBefore(ClientIdentityFilter(), UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }
}

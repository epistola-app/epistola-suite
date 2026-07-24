// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.v1

import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.api.security.ClientIdentityFilter
import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.writeProblemDetail
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.config.ApiProblemAuthenticationEntryPoint
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.annotation.Order
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper

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
@EnableMethodSecurity
class CollectSmokeSecurityConfig {

    @Bean
    @Order(1)
    fun smokeApiSecurityFilterChain(
        http: HttpSecurity,
        apiKeyService: ApiKeyService,
        meterRegistry: MeterRegistry,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        val apiKeyFilter = ApiKeyAuthenticationFilter(apiKeyService, meterRegistry, objectMapper = objectMapper)
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
            .addFilterBefore(ClientIdentityFilter(objectMapper), UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint(ApiProblemAuthenticationEntryPoint(objectMapper, bearerChallengeEnabled = false))
                exceptions.accessDeniedHandler { request, response, _ ->
                    writeProblemDetail(response, objectMapper, request, ApiProblemTypes.ACCESS_DENIED, "Access denied")
                }
            }
        return http.build()
    }
}

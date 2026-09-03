// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

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
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import tools.jackson.databind.ObjectMapper

/**
 * Test-only security wiring for [DemoSharedSecretEndToEndIT].
 *
 * The `test` profile registers a single permit-all chain, and the real
 * [app.epistola.suite.config.SecurityConfig] chains are `@Profile("!test")`, so the production
 * `/api` chain cannot be exercised by booting normally. This mirrors it — same filters, registered
 * in the same order, by the same calls — at `@Order(1)` so it matches ahead of the permit-all chain.
 *
 * Registering the filters *exactly* as production does is the point of this class rather than an
 * incidental detail. Two things about this wiring are not obvious and are not observable from a
 * filter-level unit test:
 *  - [ApiKeyAuthenticationFilter] answers 401 for any `Authorization: ApiKey` value that does not
 *    start with `epk_`, **without continuing the chain**, so the demo filter cannot simply
 *    authenticate and pass through — it hands over a principal instead.
 *  - The demo filter has to end up *before* the API-key filter, which is why it is added relative to
 *    that filter's class rather than to [UsernamePasswordAuthenticationFilter] like the others.
 *
 * Mirrors `CollectSmokeSecurityConfig`, which exists for the same reason.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSecurity
class DemoSharedSecretSecurityConfig {

    @Bean
    fun testDemoSharedSecretFilter(meterRegistry: MeterRegistry) = DemoSharedSecretAuthenticationFilter(DemoSharedSecretEndToEndIT.SHARED_SECRET, meterRegistry)

    @Bean
    @Order(1)
    fun demoSharedSecretApiFilterChain(
        http: HttpSecurity,
        apiKeyService: ApiKeyService,
        meterRegistry: MeterRegistry,
        objectMapper: ObjectMapper,
        demoFilter: DemoSharedSecretAuthenticationFilter,
    ): SecurityFilterChain {
        val apiKeyFilter = ApiKeyAuthenticationFilter(apiKeyService, meterRegistry, objectMapper = objectMapper)
        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests {
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

        // Same call, same position as SecurityConfig.apiSecurityFilterChain.
        http.addFilterBefore(demoFilter, ApiKeyAuthenticationFilter::class.java)

        return http.build()
    }
}

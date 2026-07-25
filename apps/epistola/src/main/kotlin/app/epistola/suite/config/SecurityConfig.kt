// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.api.security.ClientIdentityFilter
import app.epistola.suite.api.v1.ApiProblemTypes
import app.epistola.suite.api.v1.writeProblemDetail
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.security.AuthProperties
import app.epistola.suite.security.EpistolaJwtAuthenticationConverter
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher
import tools.jackson.databind.ObjectMapper

/**
 * Spring Security configuration with separate filter chains for API and UI.
 *
 * Authentication methods are determined by which beans are present in the context:
 * - **Form login**: Enabled when a [UserDetailsService] bean exists (e.g., [LocalUserDetailsService])
 * - **OAuth2/OIDC**: Enabled when a [ClientRegistrationRepository] bean exists
 * - **API keys**: Always enabled for /api paths (stateless, no CSRF)
 * - **JWT bearer tokens**: Enabled for /api paths when OAuth2 is configured
 *
 * Both UI auth methods can be enabled simultaneously (e.g., `local,keycloak` profiles).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig(
    private val oauth2UserProvisioningService: app.epistola.suite.security.OAuth2UserProvisioningService? = null,
    private val clientRegistrationRepository: ClientRegistrationRepository? = null,
    private val userDetailsService: UserDetailsService? = null,
    private val apiKeyService: ApiKeyService,
    private val apiKeyAuthCache: app.epistola.suite.apikeys.ApiKeyAuthCache,
    private val authProperties: AuthProperties,
    private val jwtAuthenticationConverter: EpistolaJwtAuthenticationConverter? = null,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val popupAwareAuthenticationSuccessHandler = PopupAwareAuthenticationSuccessHandler()

    /**
     * Check if OAuth2 is configured (has client registrations).
     */
    private fun hasOAuth2(): Boolean = clientRegistrationRepository != null

    /**
     * Check if form login is available (a UserDetailsService is registered).
     */
    private fun hasFormLogin(): Boolean = userDetailsService != null

    /**
     * Security for the actuator endpoints (health, info, prometheus).
     *
     * All endpoints are permitted without authentication — the production
     * hardening is **network isolation**, not auth: in production the
     * management endpoints move to a separate port (`management.server.port`,
     * see application-prod.yaml) that is kept cluster-internal and never
     * exposed via the Service/Ingress. This is the documented "permit all
     * actuator endpoints" chain (matched via [EndpointRequest.toAnyEndpoint]).
     *
     * Kubernetes probes do NOT depend on this: they hit `/livez` / `/readyz`
     * on the MAIN port (see the UI chain below and add-additional-paths),
     * because a separate management context can be up while the main app is not.
     */
    @Bean
    @Order(0)
    @Profile("!test")
    fun managementSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf { it.disable() }
        return http.build()
    }

    /**
     * API security filter chain for paths under /api.
     *
     * Stateless, no CSRF, no form login. Supports:
     * - API key authentication via Authorization: ApiKey, with legacy X-API-Key support
     * - JWT bearer tokens when OAuth2 is configured
     */
    @Bean
    @Order(1)
    @Profile("!test")
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val jwtResourceServerEnabled = hasOAuth2() && jwtAuthenticationConverter != null
        val apiKeyFilter = ApiKeyAuthenticationFilter(
            apiKeyService = apiKeyService,
            apiKeyAuthCache = apiKeyAuthCache,
            meterRegistry = meterRegistry,
            headerName = authProperties.apiKey.headerName,
            objectMapper = objectMapper,
            enabled = authProperties.apiKey.enabled,
        )

        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests {
                // /api/ping is intentionally dual-mode per the v0.3 contract: anonymous
                // probes get a basic pong (status + timestamp); authenticated callers
                // additionally receive serverVersion/apiVersion/nodeId/partition info.
                // Permit anonymous through Spring Security; the controller itself
                // decides what payload to return based on whether a principal exists.
                it.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/ping").permitAll()
                it.anyRequest().authenticated()
            }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            // Client-identity validation runs BEFORE auth so v0.3 clients calling
            // /ping or /generation/collect without X-EP-Node-Id get a clean 400
            // rather than a misleading 401. Other paths warn-only.
            .addFilterBefore(ClientIdentityFilter(objectMapper), UsernamePasswordAuthenticationFilter::class.java)
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter::class.java)
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint(ApiProblemAuthenticationEntryPoint(objectMapper, jwtResourceServerEnabled))
                exceptions.accessDeniedHandler { request, response, _ ->
                    writeProblemDetail(response, objectMapper, request, ApiProblemTypes.ACCESS_DENIED, "Access denied")
                }
            }

        // Add JWT resource server support when OAuth2/OIDC is configured
        if (jwtResourceServerEnabled) {
            http.oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)
                }
            }
        }

        return http.build()
    }

    /**
     * UI security filter chain: everything except /api paths.
     *
     * Session-based, with CSRF protection. Configures form login
     * and/or OAuth2 based on available beans.
     */
    @Bean
    @Order(2)
    @Profile("!test")
    fun uiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val formLogin = hasFormLogin()
        val oauth2 = hasOAuth2()

        http
            .authorizeHttpRequests { authorize ->
                authorize
                    // /livez + /readyz are the Kubernetes probe paths
                    // add-additional-paths exposes on the MAIN port; they are
                    // root-level (not under /actuator), so the management chain's
                    // EndpointRequest.toAnyEndpoint() matcher does not cover them
                    // and the UI chain must permit them. The actuator endpoints
                    // themselves (/actuator/**) are handled by managementSecurityFilterChain
                    // (@Order(0), permit-all) in every profile — and live on a
                    // separate port in prod — so they never reach this chain.
                    .requestMatchers("/livez", "/readyz").permitAll()
                    .requestMatchers("/login", "/login-popup-success", "/error", "/errors/**").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/fonts/**", "/images/**", "/design-system/**", "/favicon.ico").permitAll()
                // OAuth2 endpoints need to be public when OAuth2 is enabled
                if (oauth2) {
                    authorize.requestMatchers("/oauth2/**").permitAll()
                }
                authorize.anyRequest().authenticated()
            }

        // Configure form login when a UserDetailsService is available
        if (formLogin) {
            http.formLogin { form ->
                form
                    .loginPage("/login")
                    .loginProcessingUrl("/login")
                    .failureUrl("/login?error")
                    .successHandler(popupAwareAuthenticationSuccessHandler)
                    .permitAll()
            }
        }

        // Configure OAuth2 when registrations are available
        if (oauth2) {
            http.oauth2Login { oauth2Config ->
                oauth2Config
                    .loginPage("/login")
                    .successHandler(popupAwareAuthenticationSuccessHandler)
                if (oauth2UserProvisioningService != null) {
                    oauth2Config.userInfoEndpoint { userInfo ->
                        userInfo.userService(oauth2UserProvisioningService)
                        userInfo.oidcUserService(oauth2UserProvisioningService.asOidcUserService())
                    }
                }
            }
        }

        // Only save HTML page navigations in the request cache, never static resources.
        // This prevents login from redirecting to CSS/JS/image URLs.
        val htmlOnly = MediaTypeRequestMatcher(MediaType.TEXT_HTML).apply {
            setIgnoredMediaTypes(setOf(MediaType.ALL))
        }
        http.requestCache { cache ->
            cache.requestCache(HttpSessionRequestCache().apply { setRequestMatcher(htmlOnly) })
        }

        http
            .logout { logout ->
                logout
                    .logoutUrl("/logout")
                    .permitAll()
                if (oauth2 && clientRegistrationRepository != null) {
                    val oidcLogoutHandler = OidcClientInitiatedLogoutSuccessHandler(clientRegistrationRepository)
                    oidcLogoutHandler.setPostLogoutRedirectUri("{baseUrl}/login?logout")
                    logout.logoutSuccessHandler(oidcLogoutHandler)
                } else {
                    logout.logoutSuccessUrl("/login?logout")
                }
            }
            .csrf { csrf ->
                csrf.spa()
            }
            .headers { headers ->
                headers.contentSecurityPolicy { csp ->
                    // ADR 0010: script-src is strictly 'self' — no 'unsafe-inline',
                    // no external origins. Executable inline scripts and on*=
                    // attributes are banned from templates (CspTemplateComplianceTest);
                    // behavior lives in static JS, server data in inert
                    // <script type="application/json"> islands. style-src keeps
                    // 'unsafe-inline' deliberately (Option E of the ADR is deferred).
                    csp.policyDirectives(
                        "default-src 'self'; " +
                            "script-src 'self'; " +
                            "style-src 'self' 'unsafe-inline'; " +
                            "font-src 'self'; " +
                            "img-src 'self' data:; " +
                            "connect-src 'self'; " +
                            "frame-src blob:; " +
                            "frame-ancestors 'none'; " +
                            "object-src 'none'; " +
                            "base-uri 'self'",
                    )
                }
            }

        // Every UI error path content-negotiates the same way: structured callers (a request
        // that accepts JSON / problem+json, or an HTMX request) get RFC 9457 problem+json,
        // while HTML navigations keep the login redirect (401) / container error page (403).
        // Without this, Spring Security's defaults render the whitelabel /error page.
        val loginEntryPoint = LoginUrlAuthenticationEntryPoint("/login")
        http.exceptionHandling { exceptions ->
            exceptions.authenticationEntryPoint { request, response, authException ->
                if (wantsProblemDetail(request)) {
                    writeProblemDetail(response, objectMapper, request, ApiProblemTypes.UNAUTHORIZED, "Authentication required")
                } else {
                    loginEntryPoint.commence(request, response, authException)
                }
            }
            exceptions.accessDeniedHandler { request, response, _ ->
                if (wantsProblemDetail(request)) {
                    writeProblemDetail(response, objectMapper, request, ApiProblemTypes.ACCESS_DENIED, "Access denied")
                } else {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN)
                }
            }
        }

        return http.build()
    }

    /**
     * Security filter chain for test profile.
     * Permits all requests to simplify integration testing.
     */
    @Bean
    @Profile("test")
    fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { authorize ->
                authorize.anyRequest().permitAll()
            }
            .csrf { csrf ->
                csrf.disable()
            }
        return http.build()
    }
}

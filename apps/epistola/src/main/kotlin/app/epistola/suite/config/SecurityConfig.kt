package app.epistola.suite.config

import app.epistola.suite.api.security.ApiKeyAuthenticationFilter
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.security.AuthProperties
import app.epistola.suite.security.EpistolaJwtAuthenticationConverter
import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler
import io.micrometer.core.instrument.MeterRegistry
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
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.savedrequest.HttpSessionRequestCache
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher

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
    private val oidcUserProvisioningService: app.epistola.suite.security.OidcUserProvisioningService? = null,
    private val clientRegistrationRepository: ClientRegistrationRepository? = null,
    private val userDetailsService: UserDetailsService? = null,
    private val apiKeyRepository: ApiKeyRepository,
    private val apiKeyService: ApiKeyService,
    private val authProperties: AuthProperties,
    private val jwtAuthenticationConverter: EpistolaJwtAuthenticationConverter? = null,
    private val meterRegistry: MeterRegistry,
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
     * Management security filter chain for actuator endpoints.
     *
     * Runs on a separate port (management.server.port) so all endpoints are
     * permitted without authentication. Network-level access control should
     * be used to restrict access to the management port in production.
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
     * - API key authentication via X-API-Key header
     * - JWT bearer tokens when OAuth2 is configured
     */
    @Bean
    @Order(1)
    @Profile("!test")
    fun apiSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val apiKeyFilter = ApiKeyAuthenticationFilter(
            apiKeyRepository = apiKeyRepository,
            apiKeyService = apiKeyService,
            meterRegistry = meterRegistry,
            headerName = authProperties.apiKey.headerName,
        )

        http
            .securityMatcher("/api/**")
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter::class.java)

        // Add JWT resource server support when OAuth2/OIDC is configured
        if (hasOAuth2() && jwtAuthenticationConverter != null) {
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
                    // Public endpoints
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/login", "/login-popup-success", "/error").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/design-system/**", "/favicon.ico").permitAll()
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
                if (oauth2UserProvisioningService != null || oidcUserProvisioningService != null) {
                    oauth2Config.userInfoEndpoint { userInfo ->
                        if (oauth2UserProvisioningService != null) {
                            userInfo.userService(oauth2UserProvisioningService)
                        }
                        if (oidcUserProvisioningService != null) {
                            userInfo.oidcUserService(oidcUserProvisioningService)
                        }
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
                    .logoutSuccessUrl("/login?logout")
                    .permitAll()
            }
            .csrf { csrf ->
                csrf.spa()
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

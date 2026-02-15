package app.epistola.suite.config

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security configuration with additive profile-based authentication.
 *
 * Supports:
 * - Form login: Enabled when 'local' profile is active (uses LocalUserDetailsService)
 * - OAuth2/OIDC: Enabled when OAuth2 client registrations are configured
 *
 * Both can be enabled simultaneously by running with profiles like 'local,keycloak'.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig(
    private val environment: Environment,
    private val oauth2UserProvisioningService: app.epistola.suite.security.OAuth2UserProvisioningService? = null,
    private val clientRegistrationRepository: ClientRegistrationRepository? = null,
) {
    private val popupAwareAuthenticationSuccessHandler = PopupAwareAuthenticationSuccessHandler()

    /**
     * Check if OAuth2 is configured (has client registrations).
     */
    private fun isOAuth2Configured(): Boolean = clientRegistrationRepository != null

    /**
     * Check if local profile is active (form login with in-memory users).
     */
    private fun isLocalProfileActive(): Boolean = environment.acceptsProfiles(Profiles.of("local"))

    /**
     * Main security filter chain.
     * Configures form login and/or OAuth2 based on active profiles and configuration.
     */
    @Bean
    @Profile("!test")
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        val hasFormLogin = isLocalProfileActive()
        val hasOAuth2 = isOAuth2Configured()

        http
            .authorizeHttpRequests { authorize ->
                authorize
                    // Public endpoints
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/login", "/login-popup-success", "/error").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/design-system/**", "/favicon.ico").permitAll()
                // OAuth2 endpoints need to be public when OAuth2 is enabled
                if (hasOAuth2) {
                    authorize.requestMatchers("/oauth2/**").permitAll()
                }
                authorize.anyRequest().authenticated()
            }

        // Configure form login when local profile is active
        if (hasFormLogin) {
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
        if (hasOAuth2) {
            http.oauth2Login { oauth2 ->
                oauth2
                    .loginPage("/login")
                    .successHandler(popupAwareAuthenticationSuccessHandler)
                if (oauth2UserProvisioningService != null) {
                    oauth2.userInfoEndpoint { userInfo ->
                        userInfo.userService(oauth2UserProvisioningService)
                    }
                }
            }
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

package app.epistola.suite.config

import app.epistola.suite.security.PopupAwareAuthenticationSuccessHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain

/**
 * Spring Security configuration with bean-driven authentication.
 *
 * Authentication methods are determined by which beans are present in the context:
 * - **Form login**: Enabled when a [UserDetailsService] bean exists (e.g., [LocalUserDetailsService])
 * - **OAuth2/OIDC**: Enabled when a [ClientRegistrationRepository] bean exists
 *
 * Both can be enabled simultaneously (e.g., `local,keycloak` profiles).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig(
    private val oauth2UserProvisioningService: app.epistola.suite.security.OAuth2UserProvisioningService? = null,
    private val clientRegistrationRepository: ClientRegistrationRepository? = null,
    private val userDetailsService: UserDetailsService? = null,
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
     * Main security filter chain.
     * Configures form login and/or OAuth2 based on available beans.
     */
    @Bean
    @Profile("!test")
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
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
                if (oauth2UserProvisioningService != null) {
                    oauth2Config.userInfoEndpoint { userInfo ->
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

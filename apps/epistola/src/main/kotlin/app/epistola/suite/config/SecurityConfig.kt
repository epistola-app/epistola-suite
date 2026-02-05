package app.epistola.suite.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler

/**
 * Spring Security configuration with profile-based authentication.
 *
 * Supports:
 * - Local development: Form-based login with in-memory users
 * - Production: OAuth2/OIDC (Keycloak or other providers)
 *
 * The SecurityFilter binds the authenticated principal to SecurityContext
 * for use by business logic in epistola-core.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val oauth2UserProvisioningService: app.epistola.suite.security.OAuth2UserProvisioningService? = null,
) {

    /**
     * Security filter chain for local development profile.
     * Uses form-based login with in-memory users (no external dependencies).
     */
    @Bean
    @Profile("local")
    fun localSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { authorize ->
                authorize
                    // Public endpoints
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/error").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                    // All other requests require authentication
                    .anyRequest().authenticated()
            }
            .formLogin { form ->
                form
                    .loginPage("/login")
                    .defaultSuccessUrl("/", true)
                    .permitAll()
            }
            .logout { logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .permitAll()
            }
            .csrf { csrf ->
                csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
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

    /**
     * Security filter chain for production (OAuth2/OIDC).
     * Active when neither local nor test profile is active.
     */
    @Bean
    @Profile("!local & !test")
    fun oauth2SecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { authorize ->
                authorize
                    // Public endpoints
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                    .requestMatchers("/login/**", "/oauth2/**", "/error").permitAll()
                    .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                    // All other requests require authentication
                    .anyRequest().authenticated()
            }
            .oauth2Login { oauth2 ->
                oauth2
                    .loginPage("/login")
                    .defaultSuccessUrl("/", true)
                    .userInfoEndpoint { userInfo ->
                        if (oauth2UserProvisioningService != null) {
                            userInfo.userService(oauth2UserProvisioningService)
                        }
                    }
            }
            .logout { logout ->
                logout
                    .logoutUrl("/logout")
                    .logoutSuccessUrl("/login?logout")
                    .permitAll()
            }
            .csrf { csrf ->
                csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
            }

        return http.build()
    }
}

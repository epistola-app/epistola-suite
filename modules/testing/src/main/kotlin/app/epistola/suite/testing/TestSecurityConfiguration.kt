// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain

/**
 * The security of an integration test under the `test` profile: every request permitted, CSRF off.
 * The app's own chains (`SecurityConfig`) are `@Profile("!test")`; a test that needs them in place
 * builds its own chain, as `CollectSmokeSecurityConfig` does.
 *
 * Here, in the test-only library, and not in the application: on the classpath of tests alone, so a
 * deployment that activates the `test` profile by mistake gets Spring's deny-by-default, not this.
 */
@Configuration(proxyBeanMethods = false)
@Profile("test")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class TestSecurityConfiguration {

    @Bean
    fun testSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { authorize -> authorize.anyRequest().permitAll() }
            .csrf { csrf -> csrf.disable() }
        return http.build()
    }
}

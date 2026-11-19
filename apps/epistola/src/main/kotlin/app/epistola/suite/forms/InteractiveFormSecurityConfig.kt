// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.forms

import app.epistola.suite.config.contentSecurityPolicy
import app.epistola.suite.embedding.EmbeddingProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter

/**
 * The security chain of the interactive generation form, every path under `/forms/`: Suite's one page reachable
 * without signing in (contract ADR 0011). In every profile, so tests run it as production does.
 *
 * - **No login, no server session.** The person has no Epistola account. The authority is the form
 *   session a redeemed link starts, a signed cookie the core commands check; Spring Security does not
 *   create or read an HTTP session here, and never redirects to the login page.
 * - **No Spring CSRF token.** The session cookie is `SameSite=Lax`, so another site's post never
 *   carries it, and every post must also carry the form guard the page rendered.
 * - The UI's content security policy, `Referrer-Policy: no-referrer`, and no framing unless
 *   embedding is enabled.
 */
@Configuration(proxyBeanMethods = false)
class InteractiveFormSecurityConfig(private val embeddingProperties: EmbeddingProperties) {

    @Bean
    @Order(2)
    fun interactiveFormSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/forms/**")
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .securityContext { it.disable() }
            .anonymous { it.disable() }
            .csrf { it.disable() }
            .headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives(contentSecurityPolicy(embeddingProperties)) }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                if (embeddingProperties.disableXFrameOptions) headers.frameOptions { it.disable() }
            }
        return http.build()
    }
}

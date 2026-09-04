// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * Wires the demo shared secret — the one piece of demo behaviour gated on the **profile** rather
 * than on `epistola.demo.enabled`.
 *
 * Nothing else in the repo uses `@Profile("demo")`; demo behaviour is uniformly a property, and
 * `docs/auth.md` describes auth as bean-driven rather than profile-name-driven. That convention is
 * wrong for this one bean. `epistola.demo.enabled=true` is set by the `local` profile as well as by
 * `demo`, and a credential that authenticates every API endpoint as a superuser must not appear on a
 * developer's laptop merely because demo data is loaded. Please do not "fix" this to a property
 * condition — [DemoSharedSecretSafetyValidator] fails the boot if a secret is configured anywhere
 * this configuration is inactive, so the two have to agree.
 *
 * The secret itself is optional: with none configured the demo profile starts exactly as before and
 * the feature simply does not exist.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@ConditionalOnProperty(name = ["epistola.demo.enabled"], havingValue = "true")
@EnableConfigurationProperties(DemoProperties::class)
class DemoSecurityConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    @Conditional(OnDemoSharedSecretConfigured::class)
    fun demoSharedSecretAuthenticationFilter(
        properties: DemoProperties,
        meterRegistry: MeterRegistry,
    ): DemoSharedSecretAuthenticationFilter {
        require(properties.sharedSecret.length >= DemoProperties.MIN_SHARED_SECRET_LENGTH) {
            "SECURITY: epistola.demo.shared-secret must be at least " +
                "${DemoProperties.MIN_SHARED_SECRET_LENGTH} characters. It is a bearer credential " +
                "with unlimited authority over every tenant, so a guessable one is a public API."
        }

        log.warn(
            "Demo shared secret is ACTIVE: any caller presenting it authenticates against every " +
                "tenant with every permission. This is demo-profile only.",
        )
        return DemoSharedSecretAuthenticationFilter(properties.sharedSecret, meterRegistry)
    }

    /**
     * Keeps the filter out of the plain servlet chain.
     *
     * Spring Boot auto-registers every [jakarta.servlet.Filter] bean against all URLs, which would
     * run the demo filter on the UI and static resources too — outside the security chain that is
     * meant to own it. [app.epistola.suite.config.SecurityConfig] adds it to the `/api` security
     * chain explicitly; this disables the automatic registration.
     * ([app.epistola.suite.api.security.ApiKeyAuthenticationFilter] sidesteps the same problem by
     * not being a bean at all.)
     */
    @Bean
    @Conditional(OnDemoSharedSecretConfigured::class)
    fun demoSharedSecretFilterRegistration(
        filter: DemoSharedSecretAuthenticationFilter,
    ): FilterRegistrationBean<DemoSharedSecretAuthenticationFilter> = FilterRegistrationBean(filter).apply {
        isEnabled = false
    }

    /**
     * Matches when a non-blank `epistola.demo.shared-secret` is configured.
     *
     * Deliberately a [Binder] read rather than `@ConditionalOnProperty`. A property condition
     * resolves through `Environment`, which relaxes `epistola.demo.shared-secret` only to
     * `EPISTOLA_DEMO_SHARED_SECRET`, whereas `@ConfigurationProperties` binding — and therefore
     * [DemoProperties], and therefore [DemoSharedSecretSafetyValidator] — also accepts
     * `EPISTOLA_DEMO_SHAREDSECRET`. Gating on the property condition would leave an operator who
     * used that spelling with a bound secret, no filter, and nothing to tell them why. [Binder] is
     * the same mechanism `@ConfigurationProperties` uses, so the condition and the bean can never
     * disagree about whether a secret exists.
     */
    class OnDemoSharedSecretConfigured : Condition {
        override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean = Binder
            .get(context.environment)
            .bind(SHARED_SECRET_PROPERTY, String::class.java)
            .orElse("")
            .orEmpty()
            .isNotBlank()

        companion object {
            private const val SHARED_SECRET_PROPERTY = "epistola.demo.shared-secret"
        }
    }
}

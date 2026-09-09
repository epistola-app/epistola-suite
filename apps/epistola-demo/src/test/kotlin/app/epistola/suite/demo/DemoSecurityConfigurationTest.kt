// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Context-level tests for the shared-secret wiring, without booting the app.
 *
 * The bean method returns null when no secret is configured, which is an unusual enough shape that
 * "does the context still come up?" is a real question rather than a rhetorical one — the demo
 * profile has to start perfectly happily with the feature simply absent.
 */
@Tag("unit")
class DemoSecurityConfigurationTest {

    private fun contextWith(vararg properties: String) = ApplicationContextRunner()
        .withUserConfiguration(DemoSecurityConfiguration::class.java)
        .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
        .withPropertyValues("spring.profiles.active=demo", "epistola.demo.enabled=true", *properties)

    @Test
    fun `the demo profile starts normally when no secret is configured`() {
        contextWith().run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DemoSharedSecretAuthenticationFilter::class.java)
        }
    }

    @Test
    fun `a blank secret is the same as no secret`() {
        contextWith("epistola.demo.shared-secret=   ").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(DemoSharedSecretAuthenticationFilter::class.java)
        }
    }

    @Test
    fun `a configured secret produces the filter`() {
        contextWith("epistola.demo.shared-secret=$LONG_ENOUGH").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).hasSingleBean(DemoSharedSecretAuthenticationFilter::class.java)
        }
    }

    @Test
    fun `the filter is kept out of the plain servlet chain`() {
        // Spring Boot auto-registers Filter beans against all URLs; SecurityConfig adds this one to
        // the /api chain itself, so the automatic registration has to be disabled or the demo
        // credential would be honoured on the UI too.
        contextWith("epistola.demo.shared-secret=$LONG_ENOUGH").run { context ->
            val registration = context.getBean(
                org.springframework.boot.web.servlet.FilterRegistrationBean::class.java,
            )
            assertThat(registration.isEnabled).isFalse()
        }
    }

    @Test
    fun `a too-short secret fails the boot rather than shipping a guessable superuser`() {
        contextWith("epistola.demo.shared-secret=tooshort").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasRootCauseMessage(
                "SECURITY: epistola.demo.shared-secret must be at least 32 characters. It is a " +
                    "bearer credential with unlimited authority over every tenant, so a guessable " +
                    "one is a public API.",
            )
        }
    }

    @Test
    fun `outside the demo profile the wiring does not exist at all`() {
        ApplicationContextRunner()
            .withUserConfiguration(DemoSecurityConfiguration::class.java)
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withPropertyValues(
                "spring.profiles.active=local",
                "epistola.demo.enabled=true",
                "epistola.demo.shared-secret=$LONG_ENOUGH",
            )
            .run { context ->
                // `local` sets epistola.demo.enabled too — the profile is what gates this, and
                // DemoSharedSecretSafetyValidator turns this silent absence into a boot failure.
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(DemoSharedSecretAuthenticationFilter::class.java)
            }
    }

    companion object {
        /** Deliberately unmistakable and low-entropy — a realistic-looking one trips secret scanners. */
        private const val LONG_ENOUGH = "not-a-real-secret-only-used-in-tests"
    }
}

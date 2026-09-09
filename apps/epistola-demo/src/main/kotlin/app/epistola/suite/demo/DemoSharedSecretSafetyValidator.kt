// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/**
 * Fails the boot when `epistola.demo.shared-secret` is configured outside the `demo` profile.
 *
 * The secret authenticates every endpoint under `/api` against every tenant with every permission
 * ([DemoSharedSecretAuthenticationFilter]), so [DemoSecurityConfiguration] only exists under the
 * `demo` profile. Silently ignoring the variable everywhere else would be the worse failure: an
 * operator who set it in production would believe it was doing nothing *because they had configured
 * it*, rather than because the profile happened to exclude it. Refusing to start says which it is.
 *
 * Deliberately **unconditional** — this has to run in exactly the case where the rest of the demo
 * wiring does not. It reads the bound [DemoProperties] rather than the raw [Environment]:
 * `EPISTOLA_DEMO_SHAREDSECRET` reaches `@ConfigurationProperties` binding but not
 * `Environment.getProperty("epistola.demo.shared-secret")`, so a guard on the latter would miss the
 * spelling this feature documents.
 *
 * Skipped in `test`, like [app.epistola.suite.security.AuthenticationSafetyValidator], whose shape
 * this follows. Note that `demo` and `prod` are a supported combination: this guard checks that the
 * secret is confined to the demo profile, not that the deployment is non-production.
 */
@Component
@Profile("!test")
@EnableConfigurationProperties(DemoProperties::class)
class DemoSharedSecretSafetyValidator(
    private val environment: Environment,
    private val properties: DemoProperties,
) : SmartInitializingSingleton {

    override fun afterSingletonsInstantiated() {
        if (!properties.sharedSecretConfigured) return
        if (environment.acceptsProfiles(Profiles.of(DEMO_PROFILE))) return

        throw IllegalStateException(
            "SECURITY: epistola.demo.shared-secret is configured but the '$DEMO_PROFILE' profile is " +
                "not active (active profiles: ${activeProfilesLabel()}). The demo shared secret " +
                "authenticates every /api/** endpoint against every tenant with every permission, so " +
                "it is only ever honoured under '$DEMO_PROFILE' — note that 'local' enables demo data " +
                "but is not the demo profile. Either activate '$DEMO_PROFILE' or unset the secret " +
                "(EPISTOLA_DEMO_SHAREDSECRET).",
        )
    }

    private fun activeProfilesLabel(): String = environment.activeProfiles.joinToString(",").ifBlank { "none" }

    companion object {
        private const val DEMO_PROFILE = "demo"
    }
}

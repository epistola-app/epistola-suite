// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.stereotype.Component

/**
 * Safety validator that fails fast on startup when the authentication configuration is invalid.
 *
 * Checks:
 * 1. **`local` and `prod` are mutually exclusive** — `local` enables developer-only behaviour
 *    (devtools, filesystem serving, dev encryption key, in-memory users, a bundled Keycloak) that
 *    must never run in production.
 * 2. **`demo` and `prod` are mutually exclusive** — demo mode auto-creates a tenant for anyone who
 *    logs in and can carry a shared secret that authenticates every API endpoint against every
 *    tenant. Whoever can set an environment variable on a production deployment must not be one
 *    profile away from that.
 * 3. **No production with in-memory users** — combining `local` or `localauth` with `prod` would
 *    expose known/configurable passwords in a production environment.
 * 4. **At least one auth mechanism** — if neither [UserDetailsService] (form login) nor
 *    [ClientRegistrationRepository] (OAuth2) is present, the app would start but 403 everywhere.
 *
 * Skipped in `test` profile (tests use permit-all security).
 */
@Component
@Profile("!test")
class AuthenticationSafetyValidator(
    private val environment: Environment,
    private val userDetailsService: UserDetailsService? = null,
    private val clientRegistrationRepository: ClientRegistrationRepository? = null,
) : SmartInitializingSingleton {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        validateProdAndLocalAreExclusive()
        validateProdAndDemoAreExclusive()
        validateNoInMemoryUsersInProduction()
        validateAuthMechanismExists()

        logger.info(
            "Authentication configured — form login: {}, OAuth2: {}",
            userDetailsService != null,
            clientRegistrationRepository != null,
        )
    }

    private fun validateProdAndLocalAreExclusive() {
        val isProd = environment.acceptsProfiles(Profiles.of("prod"))
        val isLocal = environment.acceptsProfiles(Profiles.of("local"))

        if (isProd && isLocal) {
            throw IllegalStateException(
                "SECURITY: the 'local' and 'prod' profiles are mutually exclusive. " +
                    "'local' enables developer-only behaviour (devtools, filesystem serving, dev " +
                    "secrets, in-memory users) that must never run in production.",
            )
        }
    }

    /**
     * Demo mode is not a data-loading convenience — it changes who gets access to what. It gives
     * every person who logs in a tenant of their own, and can carry a shared secret that
     * authenticates every `/api` endpoint against every tenant with every permission.
     *
     * Refusing the combination means an operator (or anyone who can edit a deployment's environment)
     * cannot turn a production install into that by adding one profile. It is the weaker of the two
     * guarantees — the stronger one is that a production image does not ship the demo code at all —
     * but it is the one that holds inside a single image.
     */
    private fun validateProdAndDemoAreExclusive() {
        val isProd = environment.acceptsProfiles(Profiles.of("prod"))
        val isDemo = environment.acceptsProfiles(Profiles.of("demo"))

        if (isProd && isDemo) {
            throw IllegalStateException(
                "SECURITY: the 'demo' and 'prod' profiles are mutually exclusive. Demo mode gives " +
                    "every user who logs in their own tenant and can enable a shared secret that " +
                    "authenticates every API endpoint against every tenant, so it must never be one " +
                    "profile away from a production deployment. Run a demo without 'prod'.",
            )
        }
    }

    private fun validateNoInMemoryUsersInProduction() {
        val isProd = environment.acceptsProfiles(Profiles.of("prod"))
        val hasInMemoryUsers = environment.acceptsProfiles(Profiles.of("local", "localauth"))

        if (isProd && hasInMemoryUsers) {
            throw IllegalStateException(
                "SECURITY: Cannot combine 'local' or 'localauth' profile with 'prod'. " +
                    "In-memory users must not run in production.",
            )
        }
    }

    private fun validateAuthMechanismExists() {
        if (userDetailsService == null && clientRegistrationRepository == null) {
            throw IllegalStateException(
                "No authentication mechanism configured. " +
                    "Either activate a profile that provides form login (e.g., 'local', 'localauth') " +
                    "or configure OAuth2/OIDC via SPRING_SECURITY_OAUTH2_* env vars (the Helm chart's " +
                    "oidc.* values). The 'keycloak' profile is local-dev only and requires 'local'.",
            )
        }
    }
}

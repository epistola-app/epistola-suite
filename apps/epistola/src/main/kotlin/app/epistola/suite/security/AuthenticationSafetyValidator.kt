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
 * 2. **No production with in-memory users** — combining `local` or `localauth` with `prod` would
 *    expose known/configurable passwords in a production environment.
 * 3. **At least one auth mechanism** — if neither [UserDetailsService] (form login) nor
 *    [ClientRegistrationRepository] (OAuth2) is present, the app would start but 403 everywhere.
 * 4. **No shipped password outside local development** — `localauth` falls back to the passwords
 *    `admin` and `user` ([DEFAULT_PASSWORDS]) when none is configured, and its admin holds every
 *    platform role. Anywhere but alongside `local` such a password is refused: set
 *    `LOCAL_AUTH_ADMIN_PASSWORD` and `LOCAL_AUTH_USER_PASSWORD`.
 *
 * Skipped in `test` profile (tests use permit-all security).
 */
@Component
@Profile("!test")
class AuthenticationSafetyValidator(
    private val environment: Environment,
    private val userDetailsService: UserDetailsService? = null,
    private val clientRegistrationRepository: ClientRegistrationRepository? = null,
    private val authProperties: AuthProperties = AuthProperties(),
) : SmartInitializingSingleton {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterSingletonsInstantiated() {
        validateProdAndLocalAreExclusive()
        validateNoInMemoryUsersInProduction()
        validateAuthMechanismExists()
        validateNoShippedPasswordsOutsideLocal()

        log.info(
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

    private fun validateNoShippedPasswordsOutsideLocal() {
        if (environment.acceptsProfiles(Profiles.of("local"))) return
        val shipped = authProperties.localUsers.filter { it.password in DEFAULT_PASSWORDS }.map { it.username }
        if (shipped.isNotEmpty()) {
            throw IllegalStateException(
                "SECURITY: local user(s) ${shipped.joinToString()} still have the password this installation ships with. " +
                    "Outside local development set LOCAL_AUTH_ADMIN_PASSWORD and LOCAL_AUTH_USER_PASSWORD " +
                    "(Helm: config.env), or sign in through OIDC instead of 'localauth'.",
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

    companion object {
        /** The passwords `application-localauth.yaml` falls back to when none is configured. */
        val DEFAULT_PASSWORDS: Set<String> = setOf("admin", "user")
    }
}

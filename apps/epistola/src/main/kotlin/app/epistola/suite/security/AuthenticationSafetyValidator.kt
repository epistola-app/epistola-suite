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
 * 1. **No production with in-memory users** — combining `local` or `demo` profile with `prod`
 *    would expose known passwords in a production environment.
 * 2. **At least one auth mechanism** — if neither [UserDetailsService] (form login) nor
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
        validateNoInMemoryUsersInProduction()
        validateAuthMechanismExists()

        logger.info(
            "Authentication configured — form login: {}, OAuth2: {}",
            userDetailsService != null,
            clientRegistrationRepository != null,
        )
    }

    private fun validateNoInMemoryUsersInProduction() {
        val isProd = environment.acceptsProfiles(Profiles.of("prod"))
        val hasInMemoryUsers = environment.acceptsProfiles(Profiles.of("local", "demo"))

        if (isProd && hasInMemoryUsers) {
            throw IllegalStateException(
                "SECURITY: Cannot combine 'local' or 'demo' profile with 'prod'. " +
                    "In-memory users with known passwords must not run in production.",
            )
        }
    }

    private fun validateAuthMechanismExists() {
        if (userDetailsService == null && clientRegistrationRepository == null) {
            throw IllegalStateException(
                "No authentication mechanism configured. " +
                    "Either activate a profile that provides a UserDetailsService (e.g., 'local', 'demo') " +
                    "or configure OAuth2 client registrations (e.g., 'prod', 'keycloak').",
            )
        }
    }
}

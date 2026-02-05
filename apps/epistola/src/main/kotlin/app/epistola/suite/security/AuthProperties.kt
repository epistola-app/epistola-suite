package app.epistola.suite.security

import app.epistola.suite.users.AuthProvider
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for authentication.
 *
 * Controls which authentication provider to use and auto-provisioning behavior.
 */
@ConfigurationProperties(prefix = "epistola.auth")
data class AuthProperties(
    /**
     * Authentication provider to use.
     * Default: KEYCLOAK
     */
    val provider: AuthProvider = AuthProvider.KEYCLOAK,

    /**
     * OAuth2 registration ID for the provider.
     * Used to construct OAuth2 login URLs.
     * Default: keycloak
     */
    val registrationId: String = "keycloak",

    /**
     * Whether to auto-provision users on first login.
     * If false, users must be manually added to the database before they can log in.
     * Default: true
     */
    val autoProvision: Boolean = true,
)

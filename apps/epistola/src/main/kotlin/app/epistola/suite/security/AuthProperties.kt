package app.epistola.suite.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for authentication.
 *
 * Controls auto-provisioning behavior for OAuth2 users.
 */
@ConfigurationProperties(prefix = "epistola.auth")
data class AuthProperties(
    /**
     * Whether to auto-provision users on first OAuth2 login.
     * If false, users must be manually added to the database before they can log in.
     * Default: true
     */
    val autoProvision: Boolean = true,
)

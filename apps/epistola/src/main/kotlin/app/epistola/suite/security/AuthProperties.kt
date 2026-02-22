package app.epistola.suite.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for authentication.
 *
 * Controls auto-provisioning behavior for OAuth2 users and API key settings.
 */
@ConfigurationProperties(prefix = "epistola.auth")
data class AuthProperties(
    /**
     * Whether to auto-provision users on first OAuth2 login.
     * If false, users must be manually added to the database before they can log in.
     * Default: true
     */
    val autoProvision: Boolean = true,

    /**
     * API key authentication settings.
     */
    val apiKey: ApiKeyProperties = ApiKeyProperties(),
)

data class ApiKeyProperties(
    /**
     * Whether API key authentication is enabled.
     */
    val enabled: Boolean = true,

    /**
     * HTTP header name for API key authentication.
     */
    val headerName: String = "X-API-Key",
)

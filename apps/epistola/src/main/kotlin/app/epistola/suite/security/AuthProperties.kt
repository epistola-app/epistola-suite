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

    /**
     * OIDC settings for backchannel (server-to-server) communication.
     */
    val oidc: OidcProperties = OidcProperties(),
)

data class OidcProperties(
    /**
     * Internal base URL for server-to-server OIDC calls (token, JWK, userinfo).
     *
     * When set, replaces the scheme+host+port from the issuer-uri for backchannel calls,
     * while keeping the external issuer-uri for browser redirects and issuer claim validation.
     *
     * Example:
     *   issuer-uri = http://localhost:8081/realms/valtimo
     *   backchannelBaseUrl = http://keycloak:8080
     *   → token-uri becomes http://keycloak:8080/realms/valtimo/protocol/openid-connect/token
     */
    val backchannelBaseUrl: String? = null,
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

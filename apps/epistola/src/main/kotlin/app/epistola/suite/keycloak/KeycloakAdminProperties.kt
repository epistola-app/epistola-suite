package app.epistola.suite.keycloak

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the Keycloak Admin REST API client.
 *
 * Used for tenant provisioning (creating groups and organizations in Keycloak).
 */
@ConfigurationProperties(prefix = "epistola.keycloak")
data class KeycloakAdminProperties(
    /**
     * Base URL for the Keycloak Admin REST API.
     * Defaults to the backchannel base URL or localhost.
     */
    val adminUrl: String = "http://localhost:8080",

    /**
     * The Keycloak realm to manage.
     */
    val realm: String = "epistola",

    /**
     * Client ID used for client credentials authentication.
     */
    val clientId: String = "epistola-suite",

    /**
     * Client secret used for client credentials authentication.
     */
    val clientSecret: String = "",

    /** When true, ensures the base Keycloak group hierarchy exists on startup. Disabled by default. */
    val ensureGroups: Boolean = false,
)

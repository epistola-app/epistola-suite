package app.epistola.suite.users

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.TenantRole
import java.time.OffsetDateTime

/**
 * User domain model representing a global user account.
 *
 * Users have a global identity across tenants and can be members of multiple tenants.
 * Authentication is delegated to external OAuth2/OIDC providers (Keycloak, etc.) or
 * in-memory storage for local development.
 */
data class User(
    val id: UserKey,
    val externalId: String,
    val email: String,
    val displayName: String,
    val provider: AuthProvider,
    val tenantMemberships: Map<TenantKey, Set<TenantRole>>,
    val enabled: Boolean,
    val createdAt: OffsetDateTime,
    val lastLoginAt: OffsetDateTime?,
)

/**
 * Authentication provider types.
 */
enum class AuthProvider {
    /**
     * Keycloak OAuth2/OIDC provider (primary for production).
     */
    KEYCLOAK,

    /**
     * In-memory users for local development (no external dependencies).
     */
    LOCAL,

    /**
     * Generic OIDC provider (Google, Azure AD, etc.).
     */
    GENERIC_OIDC,

    /**
     * Non-human service identity backing an API key. Each API key has its own
     * `users` row (`external_id = "apikey:<id>"`) so REST writes authenticated
     * with API-key auth attribute precisely to that key in the audit columns.
     */
    API_KEY,
}

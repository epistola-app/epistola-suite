package app.epistola.suite.users

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import java.time.OffsetDateTime

/**
 * User domain model representing a global user account.
 *
 * Users have a global identity across tenants and can be members of multiple tenants.
 * Authentication is delegated to external OAuth2/OIDC providers (Keycloak, etc.) or
 * in-memory storage for local development.
 */
data class User(
    val id: UserId,
    val externalId: String,
    val email: String,
    val displayName: String,
    val provider: AuthProvider,
    val tenantMemberships: Set<TenantId>,
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
}

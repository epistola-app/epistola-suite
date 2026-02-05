package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId

/**
 * Represents an authenticated user in the Epistola Suite.
 *
 * This is the domain representation of an authenticated user, independent of
 * the authentication mechanism (OAuth2, local, etc.). It contains the essential
 * information needed for authorization and audit trails.
 *
 * The UI layer (apps/epistola) is responsible for creating instances of this
 * class from Spring Security authentication tokens (OAuth2User, UserDetails, etc.).
 */
data class EpistolaPrincipal(
    val userId: UserId,
    val externalId: String,
    val email: String,
    val displayName: String,
    val tenantMemberships: Set<TenantId>,
    val currentTenantId: TenantId?, // Can be set per-request via tenant selector
) {
    /**
     * Check if the user has access to the specified tenant.
     */
    fun hasAccessToTenant(tenantId: TenantId): Boolean = tenantMemberships.contains(tenantId)

    /**
     * Get the effective tenant ID (current or first membership if current is null).
     */
    fun effectiveTenantId(): TenantId? = currentTenantId ?: tenantMemberships.firstOrNull()
}

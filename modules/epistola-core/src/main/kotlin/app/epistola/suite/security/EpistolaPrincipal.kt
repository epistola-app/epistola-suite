package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import java.io.Serializable

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
/**
 * Marker interface for authentication wrappers that carry an [EpistolaPrincipal].
 * Implemented by LocalUserDetails and OAuth2UserWrapper so that [SecurityFilter]
 * can extract the principal without reflection.
 */
interface EpistolaPrincipalHolder {
    val epistolaPrincipal: EpistolaPrincipal
}

data class EpistolaPrincipal(
    val userId: UserKey,
    val externalId: String,
    val email: String,
    val displayName: String,
    val tenantMemberships: Map<TenantKey, TenantRole>,
    val currentTenantId: TenantKey?, // Can be set per-request via tenant selector
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 2L
    }

    /**
     * Check if the user has access to the specified tenant (any role).
     */
    fun hasAccessToTenant(tenantId: TenantKey): Boolean = tenantMemberships.containsKey(tenantId)

    /**
     * Check if the user is an admin of the specified tenant.
     */
    fun isAdmin(tenantId: TenantKey): Boolean = tenantMemberships[tenantId] == TenantRole.ADMIN

    /**
     * Get the user's role for a specific tenant, or null if not a member.
     */
    fun roleFor(tenantId: TenantKey): TenantRole? = tenantMemberships[tenantId]

    /**
     * Get the effective tenant ID (current or first membership if current is null).
     */
    fun effectiveTenantId(): TenantKey? = currentTenantId ?: tenantMemberships.keys.firstOrNull()
}

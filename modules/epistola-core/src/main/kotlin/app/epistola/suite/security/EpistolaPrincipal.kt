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
    val tenantMemberships: Map<TenantKey, Set<TenantRole>>,
    val globalRoles: Set<TenantRole> = emptySet(),
    val platformRoles: Set<PlatformRole> = emptySet(),
    val currentTenantId: TenantKey?, // Can be set per-request via tenant selector
) : Serializable {

    companion object {
        private const val serialVersionUID: Long = 5L
    }

    /**
     * Check if the user has access to the specified tenant.
     * Returns true if the user has per-tenant membership OR has any global roles
     * (global roles grant access to all tenants).
     */
    fun hasAccessToTenant(tenantId: TenantKey): Boolean = tenantMemberships.containsKey(tenantId) || globalRoles.isNotEmpty()

    /**
     * Check if the user has a specific role in the given tenant.
     * Merges per-tenant roles with global roles.
     */
    fun hasRole(tenantId: TenantKey, role: TenantRole): Boolean = role in rolesFor(tenantId)

    /**
     * Check if the user has the manager role in the specified tenant.
     */
    fun isManager(tenantId: TenantKey): Boolean = hasRole(tenantId, TenantRole.MANAGER)

    /**
     * Get the user's effective roles for a specific tenant.
     * Per-tenant roles are merged with global roles.
     */
    fun rolesFor(tenantId: TenantKey): Set<TenantRole> {
        val perTenant = tenantMemberships[tenantId] ?: emptySet()
        return if (globalRoles.isEmpty()) perTenant else perTenant + globalRoles
    }

    /**
     * Check if the user has a specific permission in the given tenant.
     * Effective permissions are the union of all the user's role grants
     * (per-tenant + global).
     */
    fun hasPermission(tenantId: TenantKey, permission: Permission): Boolean {
        val roles = rolesFor(tenantId)
        if (roles.isEmpty()) return false
        return permission in roles.effectivePermissions()
    }

    /**
     * Check if the user has the specified platform role.
     */
    fun hasPlatformRole(role: PlatformRole): Boolean = role in platformRoles

    /**
     * Check if the user is a tenant manager (can create/manage tenants).
     */
    fun isTenantManager(): Boolean = hasPlatformRole(PlatformRole.TENANT_MANAGER)

    /**
     * Get the effective tenant ID (current or first membership if current is null).
     */
    fun effectiveTenantId(): TenantKey? = currentTenantId ?: tenantMemberships.keys.firstOrNull()
}

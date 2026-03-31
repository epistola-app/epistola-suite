package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.users.User

/**
 * Optional hook to resolve tenant memberships during login when
 * the IDP provides no group-based memberships.
 *
 * Implementations are called only when the user has no `/epistola/` groups.
 */
interface LoginMembershipResolver {
    fun resolve(email: String, user: User): ResolvedMemberships?
}

data class ResolvedMemberships(
    val tenantMemberships: Map<TenantKey, Set<TenantRole>>,
    val globalRoles: Set<TenantRole> = emptySet(),
    val platformRoles: Set<PlatformRole> = emptySet(),
)

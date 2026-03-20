package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey

/**
 * Get current authenticated user (throws if not authenticated).
 *
 * @throws IllegalStateException if no user is authenticated
 */
fun currentUser(): EpistolaPrincipal = SecurityContext.current()

/**
 * Get current user ID for audit fields.
 *
 * @throws IllegalStateException if no user is authenticated
 */
fun currentUserId(): UserKey = SecurityContext.current().userId

/**
 * Get current user ID or null if not authenticated.
 * Useful for audit fields in contexts where authentication is optional.
 */
fun currentUserIdOrNull(): UserKey? = SecurityContext.currentOrNull()?.userId

/**
 * Check if current user has access to the specified tenant.
 *
 * @throws TenantAccessDeniedException if user does not have access
 * @throws IllegalStateException if no user is authenticated
 */
fun requireTenantAccess(tenantId: TenantKey) {
    val principal = SecurityContext.current()
    if (!principal.hasAccessToTenant(tenantId)) {
        throw TenantAccessDeniedException(tenantId = tenantId, userEmail = principal.email)
    }
}

/**
 * Check if current user has a specific permission in the given tenant.
 *
 * @throws PermissionDeniedException if user lacks the required permission
 * @throws IllegalStateException if no user is authenticated
 */
fun requirePermission(tenantId: TenantKey, permission: Permission) {
    val principal = SecurityContext.current()
    if (!principal.hasPermission(tenantId, permission)) {
        throw PermissionDeniedException(
            tenantId = tenantId,
            userEmail = principal.email,
            permission = permission,
        )
    }
}

/**
 * Check if current user has the tenant manager platform role.
 *
 * @throws PlatformAccessDeniedException if user is not a tenant manager
 * @throws IllegalStateException if no user is authenticated
 */
fun requireTenantManager() {
    val principal = SecurityContext.current()
    if (!principal.isTenantManager()) {
        throw PlatformAccessDeniedException(
            userEmail = principal.email,
            requiredRole = PlatformRole.TENANT_MANAGER,
        )
    }
}

/**
 * Get the effective tenant ID for the current user.
 * Uses currentTenantId if set, otherwise falls back to first membership.
 *
 * @throws IllegalStateException if no user is authenticated or user has no tenant memberships
 */
fun currentTenantId(): TenantKey = SecurityContext.current().effectiveTenantId()
    ?: throw IllegalStateException("User has no tenant memberships")

/**
 * Get the effective tenant ID or null if not authenticated or no memberships.
 */
fun currentTenantIdOrNull(): TenantKey? = SecurityContext.currentOrNull()?.effectiveTenantId()

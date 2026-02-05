package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import java.nio.file.AccessDeniedException

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
fun currentUserId(): UserId = SecurityContext.current().userId

/**
 * Get current user ID or null if not authenticated.
 * Useful for audit fields in contexts where authentication is optional.
 */
fun currentUserIdOrNull(): UserId? = SecurityContext.currentOrNull()?.userId

/**
 * Check if current user has access to the specified tenant.
 *
 * @throws AccessDeniedException if user does not have access
 * @throws IllegalStateException if no user is authenticated
 */
fun requireTenantAccess(tenantId: TenantId) {
    val principal = SecurityContext.current()
    if (!principal.hasAccessToTenant(tenantId)) {
        throw AccessDeniedException("User ${principal.email} does not have access to tenant: $tenantId")
    }
}

/**
 * Get the effective tenant ID for the current user.
 * Uses currentTenantId if set, otherwise falls back to first membership.
 *
 * @throws IllegalStateException if no user is authenticated or user has no tenant memberships
 */
fun currentTenantId(): TenantId = SecurityContext.current().effectiveTenantId()
    ?: throw IllegalStateException("User has no tenant memberships")

/**
 * Get the effective tenant ID or null if not authenticated or no memberships.
 */
fun currentTenantIdOrNull(): TenantId? = SecurityContext.currentOrNull()?.effectiveTenantId()

package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey

/**
 * Thrown when a user lacks a specific permission within a tenant.
 *
 * This is a domain-level exception (no Spring Security dependency) that is
 * translated to HTTP 403 by the API exception handler.
 */
class PermissionDeniedException(
    val tenantId: TenantKey,
    val userEmail: String,
    val permission: Permission,
) : RuntimeException("User $userEmail does not have permission $permission in tenant: $tenantId")

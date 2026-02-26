package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey

/**
 * Thrown when a user attempts to access a tenant they are not a member of.
 *
 * This is a domain-level exception (no Spring Security dependency) that is
 * translated to HTTP 403 by the API exception handler.
 */
class TenantAccessDeniedException(
    val tenantId: TenantKey,
    val userEmail: String,
) : RuntimeException("User $userEmail does not have access to tenant: $tenantId")

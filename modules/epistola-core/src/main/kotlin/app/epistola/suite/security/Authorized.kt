package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey

/**
 * Sealed interface hierarchy declaring authorization requirements for commands and queries.
 *
 * Every [Command] and [Query] must implement one of these marker interfaces.
 * The [SpringMediator] enforces the declared requirements before dispatching.
 *
 * This follows the existing marker interface pattern (TenantScoped, EntityIdentifiable)
 * and provides compile-time type safety without reflection or annotations.
 */
sealed interface Authorized

/**
 * Requires a specific [Permission] within a tenant.
 *
 * The [tenantKey] identifies which tenant's permission set to check.
 * Both tenant access and the specific permission are verified.
 */
interface RequiresPermission : Authorized {
    val permission: Permission
    val tenantKey: TenantKey
}

/**
 * Requires a platform-level role (e.g., [PlatformRole.TENANT_MANAGER]).
 *
 * Used for cross-tenant operations like creating or deleting tenants.
 */
interface RequiresPlatformRole : Authorized {
    val platformRole: PlatformRole
}

/**
 * Requires any authenticated user.
 *
 * If the command/query also implements [TenantScoped], tenant access is checked.
 * Used for operations available to all authenticated users (e.g., listing tenants).
 */
interface RequiresAuthentication : Authorized

/**
 * System-internal operation that bypasses all authorization checks.
 *
 * Used for login flows, background jobs, and internal system operations
 * where no user context exists or is needed.
 */
interface SystemInternal : Authorized

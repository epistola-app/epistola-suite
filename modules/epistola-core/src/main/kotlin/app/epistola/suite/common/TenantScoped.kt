package app.epistola.suite.common

import app.epistola.suite.common.ids.TenantKey

/**
 * Marker interface for commands and messages that are scoped to a specific tenant.
 *
 * This enables:
 * - Generic tenant extraction for event logging and audit trails
 * - Tenant-based routing and sharding for distributed processing
 * - Multi-tenant isolation at the infrastructure level
 *
 * Add to any command that operates within a tenant's scope:
 * ```kotlin
 * data class CreateTheme(...) : Command<Theme>, TenantScoped {
 *     override val tenantId: TenantId
 * }
 * ```
 *
 * Do NOT add to root-level commands like CreateTenant or DeleteTenant.
 */
interface TenantScoped {
    /**
     * The ID of the tenant this message is scoped to.
     */
    val tenantId: TenantKey
}

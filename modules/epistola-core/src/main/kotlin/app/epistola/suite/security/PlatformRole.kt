package app.epistola.suite.security

/**
 * Platform-scoped roles that apply across all tenants.
 *
 * These are sourced from Keycloak **groups** with the `ep_` prefix convention.
 * For example, `ep_tenant-manager` in the `groups` JWT claim maps to [TENANT_MANAGER].
 */
enum class PlatformRole {
    /**
     * Can create and manage tenants across the platform.
     * This is a cross-tenant role — fundamentally different from per-tenant MANAGER.
     */
    TENANT_MANAGER,
}

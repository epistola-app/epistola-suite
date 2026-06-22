package app.epistola.suite.security

/**
 * Platform-scoped roles that apply across all tenants.
 *
 * These are sourced from Keycloak **groups** using hierarchical group paths.
 * For example, `/epistola/platform/tenant-manager` in the `groups` JWT claim maps to [TENANT_MANAGER].
 */
enum class PlatformRole {
    /**
     * Can create and manage tenants across the platform.
     * This is a cross-tenant role — fundamentally different from per-tenant MANAGER.
     */
    TENANT_MANAGER,

    /**
     * Cross-tenant **read-only** access for operations and support staff:
     * diagnostics, logs, and status across all tenants. Grants no write capability.
     */
    PLATFORM_OBSERVER,
}

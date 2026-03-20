package app.epistola.suite.security

/**
 * Platform-scoped roles that apply across all tenants.
 *
 * These are sourced from Keycloak **client roles** on the `epistola-suite` client,
 * found in the JWT at `resource_access.epistola-suite.roles`. They are independent
 * of tenant-scoped roles (ADMIN/MEMBER).
 *
 * No `epistola-` prefix is needed because client roles are already namespaced
 * by the client they belong to.
 */
enum class PlatformRole {
    /**
     * Can create and manage tenants across the platform.
     * This is a cross-tenant role — fundamentally different from per-tenant ADMIN.
     */
    TENANT_MANAGER,
}

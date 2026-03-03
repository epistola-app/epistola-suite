package app.epistola.suite.security

/**
 * Role a user holds within a specific tenant.
 *
 * Roles are sourced from the IDP (Keycloak/OIDC) via the `epistola_tenants` JWT claim.
 * Each tenant membership includes a role that determines what the user can do within that tenant.
 */
enum class TenantRole {
    /** Regular tenant member with standard access. */
    MEMBER,

    /** Tenant administrator with full access including settings and user management. */
    ADMIN,
}

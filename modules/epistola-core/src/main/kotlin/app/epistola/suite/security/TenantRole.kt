package app.epistola.suite.security

/**
 * Composable roles a user can hold within a specific tenant.
 *
 * Roles are sourced from the IDP (Keycloak/OIDC) via the `groups` JWT claim
 * using hierarchical group paths.
 *
 * A user can hold multiple roles per tenant (e.g., `[reader, editor]`).
 * Each role grants a specific set of [Permission]s; the effective permissions
 * are the union of all role grants.
 *
 * Group path convention:
 * - `/epistola/tenants/acme-corp/reader` → per-tenant reader role in acme-corp
 * - `/epistola/global/reader` → global reader role (applies to all tenants)
 */
enum class TenantRole {
    /** Read-only access: view templates, themes, and documents. */
    READER,

    /** Modify templates and themes. Implies reader capabilities should also be granted. */
    EDITOR,

    /** Generate documents. Implies reader capabilities should also be granted. */
    GENERATOR,

    /** Full tenant management: publish templates, manage settings and users. */
    MANAGER,
}

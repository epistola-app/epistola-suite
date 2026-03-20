package app.epistola.suite.security

/**
 * Composable roles a user can hold within a specific tenant.
 *
 * Roles are sourced from the IDP (Keycloak/OIDC) via the `epistola_tenants` JWT claim.
 * A user can hold multiple roles per tenant (e.g., `[reader, editor]`).
 * Each role grants a specific set of [Permission]s; the effective permissions
 * are the union of all role grants.
 *
 * These align with the roles defined in the epistola-contract OpenAPI spec.
 *
 * Keycloak JWT claim format:
 * ```json
 * { "epistola_tenants": [{"id": "acme", "roles": ["reader", "editor"]}] }
 * ```
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

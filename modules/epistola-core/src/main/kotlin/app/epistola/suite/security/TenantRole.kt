package app.epistola.suite.security

/**
 * Composable roles a user can hold within a specific tenant.
 *
 * Roles are sourced from the IDP (Keycloak/OIDC) via the `groups` JWT claim
 * using hierarchical group paths.
 *
 * A user can hold multiple roles per tenant (e.g., `[content-viewer, content-author]`).
 * Each role grants a specific set of [Permission]s; the effective permissions
 * are the union of all role grants.
 *
 * Group path convention (the lowercase, kebab-case names are the IdP wire vocabulary,
 * mapped to these constants by `GroupMembershipParser` / `FlatRoleParser`):
 * - `/epistola/tenants/acme-corp/content-viewer` → per-tenant viewer role in acme-corp
 * - `/epistola/global/content-viewer` → global viewer role (applies to all tenants)
 */
enum class TenantRole {
    /** Read-only access: view templates, themes, stencils, documents, reference data, catalogs, backups. */
    CONTENT_VIEWER,

    /** Modify templates, themes, stencils, and reference data. Implies viewer capabilities should also be granted. */
    CONTENT_AUTHOR,

    /** Generate documents. Implies viewer capabilities should also be granted. */
    DOCUMENT_GENERATOR,

    /** Approve content: publish/archive template and stencil versions. Separated from tenant administration. */
    CONTENT_PUBLISHER,

    /**
     * Tenant administration: settings, users/API keys, catalog management, diagnostics, backups,
     * and the destructive restore tier. Does NOT grant publish — pair with [CONTENT_PUBLISHER] for an
     * administrator who also approves content.
     */
    TENANT_ADMINISTRATOR,
}

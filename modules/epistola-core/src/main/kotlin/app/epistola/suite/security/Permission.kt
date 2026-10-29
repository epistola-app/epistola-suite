// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

/**
 * Fine-grained permissions for actions within a tenant.
 *
 * Permissions are application-specific and derived from composable [TenantRole]s
 * within Epistola (L4 in the authorization layer model). They are NOT stored
 * in Keycloak — the IDP only provides coarse roles (reader, editor, generator, manager).
 *
 * This keeps the permission model decoupled from the IDP, allowing it to
 * evolve freely as features are added without reconfiguring identity providers.
 */
enum class Permission {
    TEMPLATE_VIEW,
    TEMPLATE_EDIT,

    STENCIL_VIEW,
    STENCIL_EDIT,

    DOCUMENT_VIEW,
    DOCUMENT_GENERATE,

    THEME_VIEW,
    THEME_EDIT,

    /** Shared reference/library data: attributes, code lists, fonts. */
    REFERENCE_VIEW,
    REFERENCE_EDIT,

    /** Catalog registry lifecycle: browse vs. register/unregister/upgrade. */
    CATALOG_VIEW,
    CATALOG_MANAGE,

    /**
     * Cut a release of a catalog version, and discard a draft (contract `releaseCatalogVersion`,
     * `deleteCatalogVersion`). Marking content ready is part of editing it.
     */
    CATALOG_RELEASE,

    /** Choose which releases an environment serves (contract `deployRelease`, `undeployRelease`). */
    ENVIRONMENT_DEPLOY,

    /**
     * Publish a catalog release to Epistola Exchange, and decide the namespace it publishes into.
     *
     * Deliberately separate from [CATALOG_RELEASE], which cuts an immutable release inside this
     * installation. Sending one to Exchange leaves the building and cannot be taken back, so being
     * trusted to release is not the same as being trusted to publish.
     */
    CATALOG_PUBLISH,

    /** Observability: application logs and diagnostics. */
    DIAGNOSTICS_VIEW,

    /**
     * Operational maintenance of diagnostics state, e.g. forgetting a dead cluster node.
     * Split from [DIAGNOSTICS_VIEW] so reading the operations pages never implies mutating
     * the runtime registries they show.
     */
    DIAGNOSTICS_MANAGE,

    /** Audit trail: view the PII-free "who did what, when" command log. */
    AUDIT_VIEW,

    /** Tenant configuration: feature toggles, defaults, environments, entitlements. */
    TENANT_SETTINGS,
    TENANT_USERS,
}

/**
 * Maps a single [TenantRole] to the set of [Permission]s it grants.
 *
 * Roles are composable — a user's effective permissions are the union of
 * all their roles' permission grants.
 */
fun TenantRole.permissions(): Set<Permission> = when (this) {
    TenantRole.CONTENT_VIEWER -> setOf(
        Permission.TEMPLATE_VIEW,
        Permission.DOCUMENT_VIEW,
        Permission.THEME_VIEW,
        Permission.STENCIL_VIEW,
        Permission.REFERENCE_VIEW,
        Permission.CATALOG_VIEW,
    )

    TenantRole.CONTENT_AUTHOR -> setOf(
        Permission.TEMPLATE_EDIT,
        Permission.THEME_EDIT,
        Permission.STENCIL_EDIT,
        Permission.REFERENCE_EDIT,
    )

    TenantRole.DOCUMENT_GENERATOR -> setOf(
        Permission.DOCUMENT_GENERATE,
    )

    TenantRole.CONTENT_PUBLISHER -> setOf(
        Permission.CATALOG_RELEASE,
        Permission.ENVIRONMENT_DEPLOY,
        Permission.CATALOG_PUBLISH,
    )

    TenantRole.TENANT_ADMINISTRATOR -> setOf(
        Permission.TENANT_SETTINGS,
        Permission.TENANT_USERS,
        Permission.CATALOG_MANAGE,
        Permission.DIAGNOSTICS_VIEW,
        Permission.DIAGNOSTICS_MANAGE,
        Permission.AUDIT_VIEW,
    )
}

/**
 * Computes the effective permissions from a set of composable roles.
 * The result is the union of all individual role permission grants.
 */
fun Set<TenantRole>.effectivePermissions(): Set<Permission> = flatMapTo(mutableSetOf()) { it.permissions() }

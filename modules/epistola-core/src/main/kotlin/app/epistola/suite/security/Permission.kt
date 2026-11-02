// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

/**
 * Fine-grained permissions for actions within a tenant.
 *
 * Permissions are application-specific and derived from composable [TenantRole]s
 * within Epistola (L4 in the authorization layer model). They are NOT stored
 * in Keycloak — the IDP only provides coarse roles.
 *
 * The contract publishes this vocabulary as data (`x-authorization` in the server stubs' spec), and
 * `PermissionContractAlignmentTest` holds the two together: every name, and which roles grant it.
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

    /**
     * Change quality profiles and bind them to a catalog or a template (contract ADR 0010, suite
     * ADR 0046). The publisher's, not the author's: a profile's rules gate releases.
     */
    QUALITY_EDIT,

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

    /**
     * Settings that send tenant data out of the installation: the Exchange connection and the
     * quality providers. Split from [TENANT_SETTINGS] like [CATALOG_PUBLISH] from [CATALOG_RELEASE]
     * (ADR 0048). No REST operation requires it.
     */
    INTEGRATIONS_MANAGE,

    /** API keys and, later, consumers: the credentials integrations call with. */
    CREDENTIALS_MANAGE,

    /** Send feedback to Epistola, and follow up on it: comments, attachments. No REST operation requires it. */
    FEEDBACK_SUBMIT,

    /** Manage the tenant's feedback: change its status, act on another member's. No REST operation requires it. */
    FEEDBACK_MANAGE,
}

/**
 * Maps a single [TenantRole] to the set of [Permission]s it grants.
 *
 * Roles are composable — a user's effective permissions are the union of
 * all their roles' permission grants.
 */
fun TenantRole.permissions(): Set<Permission> = when (this) {
    // Reviewing content is not reading its output: generated documents carry personal data, so
    // DOCUMENT_VIEW is DOCUMENT_GENERATOR's and DOCUMENT_READER's, not the viewer's (ADR 0048).
    TenantRole.CONTENT_VIEWER -> setOf(
        Permission.TEMPLATE_VIEW,
        Permission.THEME_VIEW,
        Permission.STENCIL_VIEW,
        Permission.REFERENCE_VIEW,
        Permission.CATALOG_VIEW,
        Permission.FEEDBACK_SUBMIT,
    )

    TenantRole.CONTENT_AUTHOR -> setOf(
        Permission.TEMPLATE_EDIT,
        Permission.THEME_EDIT,
        Permission.STENCIL_EDIT,
        Permission.REFERENCE_EDIT,
    )

    TenantRole.DOCUMENT_GENERATOR -> setOf(
        Permission.DOCUMENT_GENERATE,
        Permission.DOCUMENT_VIEW,
    )

    TenantRole.DOCUMENT_READER -> setOf(
        Permission.DOCUMENT_VIEW,
    )

    TenantRole.CONTENT_PUBLISHER -> setOf(
        Permission.CATALOG_RELEASE,
        Permission.ENVIRONMENT_DEPLOY,
        Permission.CATALOG_PUBLISH,
        Permission.QUALITY_EDIT,
    )

    TenantRole.TENANT_ADMINISTRATOR -> setOf(
        Permission.TENANT_SETTINGS,
        Permission.INTEGRATIONS_MANAGE,
        Permission.CREDENTIALS_MANAGE,
        Permission.FEEDBACK_MANAGE,
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

/**
 * The permissions [this] platform role grants in every tenant, read-only, whether or not the holder
 * is a member there (contract `x-authorization` `grantsInEveryTenant`).
 */
fun PlatformRole.permissionsInEveryTenant(): Set<Permission> = when (this) {
    PlatformRole.PLATFORM_OBSERVER -> setOf(Permission.DIAGNOSTICS_VIEW, Permission.AUDIT_VIEW)
    PlatformRole.TENANT_MANAGER, PlatformRole.INSTALLATION_ADMINISTRATOR -> emptySet()
}

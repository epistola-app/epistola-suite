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
    TEMPLATE_PUBLISH,

    STENCIL_VIEW,
    STENCIL_EDIT,
    STENCIL_PUBLISH,

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

    /** Observability: application logs and diagnostics. */
    DIAGNOSTICS_VIEW,

    /** Audit trail: view the PII-free "who did what, when" command log. */
    AUDIT_VIEW,

    /** Non-destructive backup operations: list backups, back up now. */
    BACKUP_VIEW,
    BACKUP_CREATE,

    /**
     * Irreversible, data-destroying operations: restore a backup or snapshot,
     * purge catalogs. Held only by MANAGER and deliberately withheld from
     * API keys by default — named to signal danger and to be granted/audited
     * independently of ordinary settings.
     */
    TENANT_RESTORE,

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
        Permission.BACKUP_VIEW,
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
        Permission.TEMPLATE_PUBLISH,
        Permission.STENCIL_PUBLISH,
    )
    TenantRole.TENANT_ADMINISTRATOR -> setOf(
        Permission.TENANT_SETTINGS,
        Permission.TENANT_USERS,
        Permission.CATALOG_MANAGE,
        Permission.BACKUP_CREATE,
        Permission.DIAGNOSTICS_VIEW,
        Permission.AUDIT_VIEW,
        Permission.TENANT_RESTORE,
    )
}

/**
 * Computes the effective permissions from a set of composable roles.
 * The result is the union of all individual role permission grants.
 */
fun Set<TenantRole>.effectivePermissions(): Set<Permission> = flatMapTo(mutableSetOf()) { it.permissions() }

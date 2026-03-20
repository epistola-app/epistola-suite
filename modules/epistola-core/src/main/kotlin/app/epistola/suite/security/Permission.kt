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

    DOCUMENT_VIEW,
    DOCUMENT_GENERATE,

    THEME_VIEW,
    THEME_EDIT,

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
    TenantRole.READER -> setOf(
        Permission.TEMPLATE_VIEW,
        Permission.DOCUMENT_VIEW,
        Permission.THEME_VIEW,
    )
    TenantRole.EDITOR -> setOf(
        Permission.TEMPLATE_EDIT,
        Permission.THEME_EDIT,
    )
    TenantRole.GENERATOR -> setOf(
        Permission.DOCUMENT_GENERATE,
    )
    TenantRole.MANAGER -> setOf(
        Permission.TEMPLATE_PUBLISH,
        Permission.TENANT_SETTINGS,
        Permission.TENANT_USERS,
    )
}

/**
 * Computes the effective permissions from a set of composable roles.
 * The result is the union of all individual role permission grants.
 */
fun Set<TenantRole>.effectivePermissions(): Set<Permission> = flatMapTo(mutableSetOf()) { it.permissions() }

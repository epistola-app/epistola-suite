package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.Permission
import app.epistola.suite.security.effectivePermissions

/**
 * Exposed to Thymeleaf templates as `auth`.
 *
 * Provides a [has] check over both tenant-scoped permissions (e.g., [Permission.TEMPLATE_EDIT])
 * and platform roles (e.g., "TENANT_MANAGER"). Kotlin callers should use the typed
 * [has]([Permission]) overload; templates use the string overload (Thymeleaf can't reference
 * enum constants), so the role-to-permission mapping stays in Kotlin and templates only name them.
 */
class AuthContext(
    private val grantedNames: Set<String>,
) {
    fun has(name: String): Boolean = name in grantedNames

    /** Type-safe permission check for Kotlin callers. */
    fun has(permission: Permission): Boolean = has(permission.name)

    companion object {
        /** No permissions granted. Used as default so templates never need null checks. */
        val NONE = AuthContext(emptySet())

        fun from(principal: EpistolaPrincipal, tenantKey: TenantKey): AuthContext {
            val names = mutableSetOf<String>()
            principal.rolesFor(tenantKey).effectivePermissions().mapTo(names) { it.name }
            principal.platformRoles.mapTo(names) { it.name }
            return AuthContext(names)
        }

        fun platformOnly(principal: EpistolaPrincipal): AuthContext {
            val names = principal.platformRoles.mapTo(mutableSetOf()) { it.name }
            return AuthContext(names)
        }
    }
}

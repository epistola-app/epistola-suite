package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.effectivePermissions

/**
 * Exposed to Thymeleaf templates as `auth`.
 *
 * Provides a single [has] method that checks both tenant-scoped permissions
 * (e.g., "TEMPLATE_EDIT") and platform roles (e.g., "TENANT_MANAGER").
 * The role-to-permission mapping stays in Kotlin code — templates only
 * reference permission/role names as strings.
 */
class AuthContext(
    private val grantedNames: Set<String>,
) {
    fun has(name: String): Boolean = name in grantedNames

    companion object {
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

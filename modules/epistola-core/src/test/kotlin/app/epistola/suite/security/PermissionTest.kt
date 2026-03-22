package app.epistola.suite.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PermissionTest {

    @Test
    fun `READER role grants view-only permissions`() {
        val permissions = TenantRole.READER.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_VIEW,
            Permission.DOCUMENT_VIEW,
            Permission.THEME_VIEW,
        )
    }

    @Test
    fun `EDITOR role grants edit permissions`() {
        val permissions = TenantRole.EDITOR.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_EDIT,
            Permission.THEME_EDIT,
        )
    }

    @Test
    fun `GENERATOR role grants document generation permission`() {
        val permissions = TenantRole.GENERATOR.permissions()
        assertThat(permissions).containsExactly(Permission.DOCUMENT_GENERATE)
    }

    @Test
    fun `MANAGER role grants publish and tenant management permissions`() {
        val permissions = TenantRole.MANAGER.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_PUBLISH,
            Permission.TENANT_SETTINGS,
            Permission.TENANT_USERS,
        )
    }

    @Test
    fun `all roles combined grant all permissions`() {
        val allPermissions = TenantRole.entries.toSet().effectivePermissions()
        assertThat(allPermissions).containsExactlyInAnyOrderElementsOf(Permission.entries)
    }

    @Test
    fun `reader plus editor grants view and edit but not generate or manage`() {
        val permissions = setOf(TenantRole.READER, TenantRole.EDITOR).effectivePermissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_VIEW,
            Permission.TEMPLATE_EDIT,
            Permission.DOCUMENT_VIEW,
            Permission.THEME_VIEW,
            Permission.THEME_EDIT,
        )
    }

    @Test
    fun `reader plus generator grants view and generate but not edit`() {
        val permissions = setOf(TenantRole.READER, TenantRole.GENERATOR).effectivePermissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_VIEW,
            Permission.DOCUMENT_VIEW,
            Permission.DOCUMENT_GENERATE,
            Permission.THEME_VIEW,
        )
    }
}

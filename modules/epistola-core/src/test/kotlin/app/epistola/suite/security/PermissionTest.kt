package app.epistola.suite.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PermissionTest {

    @Test
    fun `CONTENT_VIEWER role grants view-only permissions`() {
        val permissions = TenantRole.CONTENT_VIEWER.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_VIEW,
            Permission.DOCUMENT_VIEW,
            Permission.THEME_VIEW,
            Permission.STENCIL_VIEW,
            Permission.REFERENCE_VIEW,
            Permission.CATALOG_VIEW,
            Permission.BACKUP_VIEW,
        )
    }

    @Test
    fun `CONTENT_AUTHOR role grants edit permissions`() {
        val permissions = TenantRole.CONTENT_AUTHOR.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_EDIT,
            Permission.THEME_EDIT,
            Permission.STENCIL_EDIT,
            Permission.REFERENCE_EDIT,
        )
    }

    @Test
    fun `DOCUMENT_GENERATOR role grants document generation permission`() {
        val permissions = TenantRole.DOCUMENT_GENERATOR.permissions()
        assertThat(permissions).containsExactly(Permission.DOCUMENT_GENERATE)
    }

    @Test
    fun `CONTENT_PUBLISHER role grants publish permissions only`() {
        val permissions = TenantRole.CONTENT_PUBLISHER.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_PUBLISH,
            Permission.STENCIL_PUBLISH,
        )
    }

    @Test
    fun `TENANT_ADMINISTRATOR grants administration but not publish`() {
        val permissions = TenantRole.TENANT_ADMINISTRATOR.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TENANT_SETTINGS,
            Permission.TENANT_USERS,
            Permission.CATALOG_MANAGE,
            Permission.BACKUP_CREATE,
            Permission.DIAGNOSTICS_VIEW,
            Permission.TENANT_RESTORE,
        )
        assertThat(permissions).doesNotContain(Permission.TEMPLATE_PUBLISH, Permission.STENCIL_PUBLISH)
    }

    @Test
    fun `all roles combined grant all permissions`() {
        val allPermissions = TenantRole.entries.toSet().effectivePermissions()
        assertThat(allPermissions).containsExactlyInAnyOrderElementsOf(Permission.entries)
    }

    @Test
    fun `viewer plus author grants view and edit but not generate or administer`() {
        val permissions = setOf(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR).effectivePermissions()
        assertThat(permissions).contains(
            Permission.TEMPLATE_VIEW,
            Permission.TEMPLATE_EDIT,
            Permission.REFERENCE_VIEW,
            Permission.REFERENCE_EDIT,
        )
        assertThat(permissions).doesNotContain(
            Permission.DOCUMENT_GENERATE,
            Permission.TENANT_SETTINGS,
            Permission.TENANT_RESTORE,
            Permission.TEMPLATE_PUBLISH,
        )
    }

    @Test
    fun `viewer plus generator grants view and generate but not edit`() {
        val permissions = setOf(TenantRole.CONTENT_VIEWER, TenantRole.DOCUMENT_GENERATOR).effectivePermissions()
        assertThat(permissions).contains(
            Permission.TEMPLATE_VIEW,
            Permission.DOCUMENT_VIEW,
            Permission.DOCUMENT_GENERATE,
        )
        assertThat(permissions).doesNotContain(
            Permission.TEMPLATE_EDIT,
            Permission.REFERENCE_EDIT,
        )
    }
}

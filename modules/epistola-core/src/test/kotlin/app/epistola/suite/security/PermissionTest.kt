// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class PermissionTest {

    @Test
    fun `CONTENT_VIEWER role grants view-only permissions, not generated documents`() {
        val permissions = TenantRole.CONTENT_VIEWER.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TEMPLATE_VIEW,
            Permission.THEME_VIEW,
            Permission.STENCIL_VIEW,
            Permission.REFERENCE_VIEW,
            Permission.CATALOG_VIEW,
            Permission.FEEDBACK_SUBMIT,
        )
        // Reviewing content is not reading its output (ADR 0048): documents carry personal data.
        assertThat(permissions).doesNotContain(Permission.DOCUMENT_VIEW)
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
    fun `DOCUMENT_GENERATOR role generates documents and reads them`() {
        val permissions = TenantRole.DOCUMENT_GENERATOR.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(Permission.DOCUMENT_GENERATE, Permission.DOCUMENT_VIEW)
    }

    @Test
    fun `DOCUMENT_READER role reads documents without generating`() {
        assertThat(TenantRole.DOCUMENT_READER.permissions()).containsExactly(Permission.DOCUMENT_VIEW)
    }

    @Test
    fun `PLATFORM_OBSERVER grants diagnostics and audit reads in every tenant, nothing else`() {
        assertThat(PlatformRole.PLATFORM_OBSERVER.permissionsInEveryTenant()).containsExactlyInAnyOrder(Permission.DIAGNOSTICS_VIEW, Permission.AUDIT_VIEW)
        assertThat(PlatformRole.TENANT_MANAGER.permissionsInEveryTenant()).isEmpty()
        assertThat(PlatformRole.INSTALLATION_ADMINISTRATOR.permissionsInEveryTenant()).isEmpty()
    }

    @Test
    fun `CONTENT_PUBLISHER role grants releasing, deploying and publishing only`() {
        val permissions = TenantRole.CONTENT_PUBLISHER.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            // Contract 2.0: release and deploy replace template and stencil publish.
            Permission.CATALOG_RELEASE,
            Permission.ENVIRONMENT_DEPLOY,
            // Publishing a catalog to Exchange is a publisher's act, not an administrator's —
            // see the TENANT_ADMINISTRATOR case below, which grants administration but not publish.
            Permission.CATALOG_PUBLISH,
            // A quality profile's rules gate releases: the publisher's, not the author's.
            Permission.QUALITY_EDIT,
        )
    }

    @Test
    fun `TENANT_ADMINISTRATOR grants administration but not publish`() {
        val permissions = TenantRole.TENANT_ADMINISTRATOR.permissions()
        assertThat(permissions).containsExactlyInAnyOrder(
            Permission.TENANT_SETTINGS,
            Permission.INTEGRATIONS_MANAGE,
            Permission.CREDENTIALS_MANAGE,
            Permission.FEEDBACK_MANAGE,
            Permission.CATALOG_MANAGE,
            Permission.DIAGNOSTICS_VIEW,
            Permission.DIAGNOSTICS_MANAGE,
            Permission.AUDIT_VIEW,
        )
        assertThat(permissions).doesNotContain(Permission.CATALOG_RELEASE, Permission.ENVIRONMENT_DEPLOY)
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
            Permission.CATALOG_RELEASE,
            Permission.ENVIRONMENT_DEPLOY,
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

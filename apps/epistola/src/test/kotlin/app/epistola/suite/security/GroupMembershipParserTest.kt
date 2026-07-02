package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class GroupMembershipParserTest {

    @Test
    fun `parses per-tenant roles from hierarchical groups`() {
        val result = parseGroupMemberships(
            listOf("/epistola/tenants/acme-corp/content-viewer", "/epistola/tenants/acme-corp/content-author"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
    }

    @Test
    fun `parses multiple tenants`() {
        val result = parseGroupMemberships(
            listOf("/epistola/tenants/acme-corp/content-viewer", "/epistola/tenants/beta-org/tenant-administrator"),
        )

        assertThat(result.tenantRoles).hasSize(2)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(result.tenantRoles[TenantKey.of("beta-org")]).containsExactly(TenantRole.TENANT_ADMINISTRATOR)
    }

    @Test
    fun `parses global roles`() {
        val result = parseGroupMemberships(listOf("/epistola/global/content-viewer", "/epistola/global/content-author"))

        assertThat(result.globalRoles).containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(result.tenantRoles).isEmpty()
    }

    @Test
    fun `parses platform roles`() {
        val result = parseGroupMemberships(listOf("/epistola/platform/tenant-manager"))

        assertThat(result.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.tenantRoles).isEmpty()
    }

    @Test
    fun `ignores non-ep groups`() {
        val result = parseGroupMemberships(
            listOf("/other-app/group", "admin", "/epistola/tenants/acme-corp/content-viewer"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `ignores unknown roles`() {
        val result = parseGroupMemberships(
            listOf("/epistola/tenants/acme-corp/superadmin", "/epistola/tenants/acme-corp/content-viewer"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `ignores groups with invalid tenant keys`() {
        val result = parseGroupMemberships(
            listOf("/epistola/tenants/INVALID/content-viewer", "/epistola/tenants/acme-corp/content-viewer"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `returns empty for empty groups list`() {
        val result = parseGroupMemberships(emptyList())

        assertThat(result.tenantRoles).isEmpty()
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `returns empty for no ep groups`() {
        val result = parseGroupMemberships(listOf("admin", "users", "managers"))

        assertThat(result.tenantRoles).isEmpty()
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `handles all four tenant roles`() {
        val result = parseGroupMemberships(
            listOf(
                "/epistola/tenants/acme-corp/content-viewer",
                "/epistola/tenants/acme-corp/content-author",
                "/epistola/tenants/acme-corp/document-generator",
                "/epistola/tenants/acme-corp/tenant-administrator",
            ),
        )

        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(
                TenantRole.CONTENT_VIEWER,
                TenantRole.CONTENT_AUTHOR,
                TenantRole.DOCUMENT_GENERATOR,
                TenantRole.TENANT_ADMINISTRATOR,
            )
    }

    @Test
    fun `parses tenant keys with hyphens`() {
        val result = parseGroupMemberships(listOf("/epistola/tenants/my-company/content-viewer"))

        assertThat(result.tenantRoles[TenantKey.of("my-company")]).containsExactly(TenantRole.CONTENT_VIEWER)
    }

    @Test
    fun `full example with all group types`() {
        val result = parseGroupMemberships(
            listOf(
                "/epistola/tenants/acme-corp/content-viewer",
                "/epistola/tenants/acme-corp/content-author",
                "/epistola/global/content-viewer",
                "/epistola/platform/tenant-manager",
                "/other-app/group",
            ),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.CONTENT_VIEWER, TenantRole.CONTENT_AUTHOR)
        assertThat(result.globalRoles).containsExactly(TenantRole.CONTENT_VIEWER)
        assertThat(result.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `ignores intermediate path groups`() {
        val result = parseGroupMemberships(listOf("/epistola", "/epistola/tenants", "/epistola/tenants/demo"))

        assertThat(result.tenantRoles).isEmpty()
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `ignores unknown category`() {
        val result = parseGroupMemberships(listOf("/epistola/unknown/something"))

        assertThat(result.tenantRoles).isEmpty()
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `ignores unknown global role`() {
        val result = parseGroupMemberships(listOf("/epistola/global/unknown"))

        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `ignores unknown platform role`() {
        val result = parseGroupMemberships(listOf("/epistola/platform/unknown"))

        assertThat(result.platformRoles).isEmpty()
    }
}

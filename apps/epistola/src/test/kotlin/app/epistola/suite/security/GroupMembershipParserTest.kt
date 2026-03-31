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
            listOf("/epistola/tenants/acme-corp/reader", "/epistola/tenants/acme-corp/editor"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
    }

    @Test
    fun `parses multiple tenants`() {
        val result = parseGroupMemberships(
            listOf("/epistola/tenants/acme-corp/reader", "/epistola/tenants/beta-org/manager"),
        )

        assertThat(result.tenantRoles).hasSize(2)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
        assertThat(result.tenantRoles[TenantKey.of("beta-org")]).containsExactly(TenantRole.MANAGER)
    }

    @Test
    fun `parses global roles`() {
        val result = parseGroupMemberships(listOf("/epistola/global/reader", "/epistola/global/editor"))

        assertThat(result.globalRoles).containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
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
            listOf("/other-app/group", "admin", "/epistola/tenants/acme-corp/reader"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
    }

    @Test
    fun `ignores unknown roles`() {
        val result = parseGroupMemberships(
            listOf("/epistola/tenants/acme-corp/superadmin", "/epistola/tenants/acme-corp/reader"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
    }

    @Test
    fun `ignores groups with invalid tenant keys`() {
        val result = parseGroupMemberships(
            listOf("/epistola/tenants/INVALID/reader", "/epistola/tenants/acme-corp/reader"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
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
                "/epistola/tenants/acme-corp/reader",
                "/epistola/tenants/acme-corp/editor",
                "/epistola/tenants/acme-corp/generator",
                "/epistola/tenants/acme-corp/manager",
            ),
        )

        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(
                TenantRole.READER,
                TenantRole.EDITOR,
                TenantRole.GENERATOR,
                TenantRole.MANAGER,
            )
    }

    @Test
    fun `parses tenant keys with hyphens`() {
        val result = parseGroupMemberships(listOf("/epistola/tenants/my-company/reader"))

        assertThat(result.tenantRoles[TenantKey.of("my-company")]).containsExactly(TenantRole.READER)
    }

    @Test
    fun `full example with all group types`() {
        val result = parseGroupMemberships(
            listOf(
                "/epistola/tenants/acme-corp/reader",
                "/epistola/tenants/acme-corp/editor",
                "/epistola/global/reader",
                "/epistola/platform/tenant-manager",
                "/other-app/group",
            ),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
        assertThat(result.globalRoles).containsExactly(TenantRole.READER)
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

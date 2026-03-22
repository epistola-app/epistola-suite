package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class GroupMembershipParserTest {

    @Test
    fun `parses per-tenant roles from ep-prefixed groups`() {
        val result = parseGroupMemberships(
            listOf("ep_acme-corp_reader", "ep_acme-corp_editor"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
    }

    @Test
    fun `parses multiple tenants`() {
        val result = parseGroupMemberships(
            listOf("ep_acme-corp_reader", "ep_beta-org_manager"),
        )

        assertThat(result.tenantRoles).hasSize(2)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
        assertThat(result.tenantRoles[TenantKey.of("beta-org")]).containsExactly(TenantRole.MANAGER)
    }

    @Test
    fun `parses global roles (no tenant prefix)`() {
        val result = parseGroupMemberships(listOf("ep_reader", "ep_editor"))

        assertThat(result.globalRoles).containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
        assertThat(result.tenantRoles).isEmpty()
    }

    @Test
    fun `parses platform roles`() {
        val result = parseGroupMemberships(listOf("ep_tenant-manager"))

        assertThat(result.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.tenantRoles).isEmpty()
    }

    @Test
    fun `ignores non-ep groups`() {
        val result = parseGroupMemberships(
            listOf("other-app-group", "admin", "ep_acme-corp_reader"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
    }

    @Test
    fun `ignores unknown roles`() {
        val result = parseGroupMemberships(
            listOf("ep_acme-corp_superadmin", "ep_acme-corp_reader"),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")]).containsExactly(TenantRole.READER)
    }

    @Test
    fun `ignores groups with invalid tenant keys`() {
        val result = parseGroupMemberships(
            listOf("ep_INVALID_reader", "ep_acme-corp_reader"),
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
                "ep_acme-corp_reader",
                "ep_acme-corp_editor",
                "ep_acme-corp_generator",
                "ep_acme-corp_manager",
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
    fun `splits on last underscore for tenant keys with hyphens`() {
        val result = parseGroupMemberships(listOf("ep_my-company_reader"))

        assertThat(result.tenantRoles[TenantKey.of("my-company")]).containsExactly(TenantRole.READER)
    }

    @Test
    fun `full example from plan`() {
        val result = parseGroupMemberships(
            listOf(
                "ep_acme-corp_reader",
                "ep_acme-corp_editor",
                "ep_reader",
                "ep_tenant-manager",
                "other-app-group",
            ),
        )

        assertThat(result.tenantRoles).hasSize(1)
        assertThat(result.tenantRoles[TenantKey.of("acme-corp")])
            .containsExactlyInAnyOrder(TenantRole.READER, TenantRole.EDITOR)
        assertThat(result.globalRoles).containsExactly(TenantRole.READER)
        assertThat(result.platformRoles).containsExactly(PlatformRole.TENANT_MANAGER)
    }

    @Test
    fun `ignores ep_ with empty remainder`() {
        val result = parseGroupMemberships(listOf("ep_"))

        assertThat(result.tenantRoles).isEmpty()
        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }

    @Test
    fun `ignores unknown global role`() {
        val result = parseGroupMemberships(listOf("ep_unknown"))

        assertThat(result.globalRoles).isEmpty()
        assertThat(result.platformRoles).isEmpty()
    }
}

package app.epistola.suite.security

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class EpistolaPrincipalTest {

    private val acme = TenantKey.of("acme")
    private val beta = TenantKey.of("beta")
    private val unknown = TenantKey.of("unknown")

    private fun principal(
        memberships: Map<TenantKey, Set<TenantRole>> = mapOf(
            acme to setOf(TenantRole.READER, TenantRole.EDITOR, TenantRole.GENERATOR, TenantRole.MANAGER),
            beta to setOf(TenantRole.READER, TenantRole.EDITOR),
        ),
        platformRoles: Set<PlatformRole> = emptySet(),
    ) = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-000000000001"),
        externalId = "test-user",
        email = "test@example.com",
        displayName = "Test User",
        tenantMemberships = memberships,
        platformRoles = platformRoles,
        currentTenantId = null,
    )

    @Test
    fun `user with all roles has all permissions`() {
        val p = principal()
        Permission.entries.forEach { permission ->
            assertThat(p.hasPermission(acme, permission))
                .withFailMessage("All-roles user should have $permission")
                .isTrue()
        }
    }

    @Test
    fun `reader plus editor grants view and edit but not generate`() {
        val p = principal()
        assertThat(p.hasPermission(beta, Permission.TEMPLATE_VIEW)).isTrue()
        assertThat(p.hasPermission(beta, Permission.TEMPLATE_EDIT)).isTrue()
        assertThat(p.hasPermission(beta, Permission.THEME_VIEW)).isTrue()
        assertThat(p.hasPermission(beta, Permission.THEME_EDIT)).isTrue()
        assertThat(p.hasPermission(beta, Permission.DOCUMENT_GENERATE)).isFalse()
        assertThat(p.hasPermission(beta, Permission.TENANT_SETTINGS)).isFalse()
    }

    @Test
    fun `hasPermission returns false for non-member tenant`() {
        val p = principal()
        assertThat(p.hasPermission(unknown, Permission.TEMPLATE_VIEW)).isFalse()
    }

    @Test
    fun `hasRole checks specific role`() {
        val p = principal()
        assertThat(p.hasRole(acme, TenantRole.MANAGER)).isTrue()
        assertThat(p.hasRole(beta, TenantRole.MANAGER)).isFalse()
        assertThat(p.hasRole(beta, TenantRole.READER)).isTrue()
    }

    @Test
    fun `isManager checks for MANAGER role`() {
        val p = principal()
        assertThat(p.isManager(acme)).isTrue()
        assertThat(p.isManager(beta)).isFalse()
    }

    @Test
    fun `rolesFor returns roles or empty set`() {
        val p = principal()
        assertThat(p.rolesFor(acme)).containsExactlyInAnyOrder(
            TenantRole.READER,
            TenantRole.EDITOR,
            TenantRole.GENERATOR,
            TenantRole.MANAGER,
        )
        assertThat(p.rolesFor(unknown)).isEmpty()
    }

    @Test
    fun `hasPlatformRole checks platform roles`() {
        val p = principal(platformRoles = setOf(PlatformRole.TENANT_MANAGER))
        assertThat(p.hasPlatformRole(PlatformRole.TENANT_MANAGER)).isTrue()
    }

    @Test
    fun `hasPlatformRole returns false when role not present`() {
        val p = principal()
        assertThat(p.hasPlatformRole(PlatformRole.TENANT_MANAGER)).isFalse()
    }

    @Test
    fun `isTenantManager is a convenience for TENANT_MANAGER platform role`() {
        assertThat(principal().isTenantManager()).isFalse()
        assertThat(principal(platformRoles = setOf(PlatformRole.TENANT_MANAGER)).isTenantManager()).isTrue()
    }

    @Test
    fun `platform roles and tenant roles are independent`() {
        val p = principal(
            memberships = mapOf(acme to setOf(TenantRole.READER)),
            platformRoles = setOf(PlatformRole.TENANT_MANAGER),
        )

        // Has platform role
        assertThat(p.isTenantManager()).isTrue()

        // But only READER in acme (can view, cannot edit or manage)
        assertThat(p.isManager(acme)).isFalse()
        assertThat(p.hasPermission(acme, Permission.TEMPLATE_VIEW)).isTrue()
        assertThat(p.hasPermission(acme, Permission.TEMPLATE_EDIT)).isFalse()
        assertThat(p.hasPermission(acme, Permission.TENANT_SETTINGS)).isFalse()
    }
}

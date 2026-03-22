package app.epistola.suite.handlers

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class AuthContextTest {

    private val acme = TenantKey.of("acme")

    private fun principal(
        tenantMemberships: Map<TenantKey, Set<TenantRole>> = emptyMap(),
        globalRoles: Set<TenantRole> = emptySet(),
        platformRoles: Set<PlatformRole> = emptySet(),
    ) = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-000000000001"),
        externalId = "test",
        email = "test@example.com",
        displayName = "Test",
        tenantMemberships = tenantMemberships,
        globalRoles = globalRoles,
        platformRoles = platformRoles,
        currentTenantId = null,
    )

    @Test
    fun `NONE denies everything`() {
        assertThat(AuthContext.NONE.has("TEMPLATE_VIEW")).isFalse()
        assertThat(AuthContext.NONE.has("TENANT_MANAGER")).isFalse()
    }

    @Test
    fun `from() includes tenant permission names`() {
        val auth = AuthContext.from(
            principal(tenantMemberships = mapOf(acme to setOf(TenantRole.READER))),
            acme,
        )

        assertThat(auth.has("TEMPLATE_VIEW")).isTrue()
        assertThat(auth.has("DOCUMENT_VIEW")).isTrue()
        assertThat(auth.has("THEME_VIEW")).isTrue()
        assertThat(auth.has("TEMPLATE_EDIT")).isFalse()
    }

    @Test
    fun `from() merges multiple roles`() {
        val auth = AuthContext.from(
            principal(tenantMemberships = mapOf(acme to setOf(TenantRole.READER, TenantRole.EDITOR))),
            acme,
        )

        assertThat(auth.has("TEMPLATE_VIEW")).isTrue()
        assertThat(auth.has("TEMPLATE_EDIT")).isTrue()
        assertThat(auth.has("DOCUMENT_GENERATE")).isFalse()
    }

    @Test
    fun `from() includes platform roles`() {
        val auth = AuthContext.from(
            principal(
                tenantMemberships = mapOf(acme to setOf(TenantRole.READER)),
                platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            ),
            acme,
        )

        assertThat(auth.has("TENANT_MANAGER")).isTrue()
        assertThat(auth.has("TEMPLATE_VIEW")).isTrue()
    }

    @Test
    fun `from() includes global roles for the tenant`() {
        val auth = AuthContext.from(
            principal(globalRoles = setOf(TenantRole.MANAGER)),
            acme,
        )

        assertThat(auth.has("TENANT_SETTINGS")).isTrue()
        assertThat(auth.has("TEMPLATE_PUBLISH")).isTrue()
    }

    @Test
    fun `from() returns empty when user has no access to tenant`() {
        val other = TenantKey.of("other")
        val auth = AuthContext.from(
            principal(tenantMemberships = mapOf(acme to setOf(TenantRole.READER))),
            other,
        )

        assertThat(auth.has("TEMPLATE_VIEW")).isFalse()
    }

    @Test
    fun `platformOnly() only includes platform roles`() {
        val auth = AuthContext.platformOnly(
            principal(
                tenantMemberships = mapOf(acme to setOf(TenantRole.READER)),
                platformRoles = setOf(PlatformRole.TENANT_MANAGER),
            ),
        )

        assertThat(auth.has("TENANT_MANAGER")).isTrue()
        assertThat(auth.has("TEMPLATE_VIEW")).isFalse()
    }

    @Test
    fun `has() returns false for unknown names`() {
        val auth = AuthContext.from(
            principal(tenantMemberships = mapOf(acme to TenantRole.entries.toSet())),
            acme,
        )

        assertThat(auth.has("NONEXISTENT_PERMISSION")).isFalse()
    }
}

package app.epistola.suite.keycloak

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class KeycloakTenantProvisionerTest {

    @Test
    fun `generates correct group names for all roles`() {
        val tenantKey = TenantKey.of("acme-corp")

        assertThat(KeycloakTenantProvisioner.groupNameFor(tenantKey, TenantRole.READER))
            .isEqualTo("ep_acme-corp_reader")
        assertThat(KeycloakTenantProvisioner.groupNameFor(tenantKey, TenantRole.EDITOR))
            .isEqualTo("ep_acme-corp_editor")
        assertThat(KeycloakTenantProvisioner.groupNameFor(tenantKey, TenantRole.GENERATOR))
            .isEqualTo("ep_acme-corp_generator")
        assertThat(KeycloakTenantProvisioner.groupNameFor(tenantKey, TenantRole.MANAGER))
            .isEqualTo("ep_acme-corp_manager")
    }

    @Test
    fun `group names use lowercase role names`() {
        val tenantKey = TenantKey.of("beta")

        TenantRole.entries.forEach { role ->
            val groupName = KeycloakTenantProvisioner.groupNameFor(tenantKey, role)
            assertThat(groupName).isEqualTo("ep_beta_${role.name.lowercase()}")
        }
    }

    @Test
    fun `group names follow ep prefix convention`() {
        val tenantKey = TenantKey.of("my-tenant")

        TenantRole.entries.forEach { role ->
            val groupName = KeycloakTenantProvisioner.groupNameFor(tenantKey, role)
            assertThat(groupName).startsWith("ep_")
        }
    }
}

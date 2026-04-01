package app.epistola.suite.keycloak

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.TenantRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class KeycloakTenantProvisionerTest {

    @Test
    fun `generates correct group paths for all roles`() {
        val tenantKey = TenantKey.of("acme-corp")

        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.READER))
            .isEqualTo("/epistola/tenants/acme-corp/reader")
        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.EDITOR))
            .isEqualTo("/epistola/tenants/acme-corp/editor")
        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.GENERATOR))
            .isEqualTo("/epistola/tenants/acme-corp/generator")
        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.MANAGER))
            .isEqualTo("/epistola/tenants/acme-corp/manager")
    }

    @Test
    fun `group paths use lowercase role names`() {
        val tenantKey = TenantKey.of("beta")

        TenantRole.entries.forEach { role ->
            val groupPath = KeycloakTenantProvisioner.groupPathFor(tenantKey, role)
            assertThat(groupPath).isEqualTo("/epistola/tenants/beta/${role.name.lowercase()}")
        }
    }

    @Test
    fun `group paths follow hierarchical convention`() {
        val tenantKey = TenantKey.of("my-tenant")

        TenantRole.entries.forEach { role ->
            val groupPath = KeycloakTenantProvisioner.groupPathFor(tenantKey, role)
            assertThat(groupPath).startsWith("/epistola/tenants/")
        }
    }
}

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

        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.CONTENT_VIEWER))
            .isEqualTo("/epistola/tenants/acme-corp/content-viewer")
        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.CONTENT_AUTHOR))
            .isEqualTo("/epistola/tenants/acme-corp/content-author")
        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.DOCUMENT_GENERATOR))
            .isEqualTo("/epistola/tenants/acme-corp/document-generator")
        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.CONTENT_PUBLISHER))
            .isEqualTo("/epistola/tenants/acme-corp/content-publisher")
        assertThat(KeycloakTenantProvisioner.groupPathFor(tenantKey, TenantRole.TENANT_ADMINISTRATOR))
            .isEqualTo("/epistola/tenants/acme-corp/tenant-administrator")
    }

    @Test
    fun `group paths use kebab-case role names`() {
        val tenantKey = TenantKey.of("beta")

        TenantRole.entries.forEach { role ->
            val groupPath = KeycloakTenantProvisioner.groupPathFor(tenantKey, role)
            assertThat(groupPath).isEqualTo("/epistola/tenants/beta/${role.name.lowercase().replace('_', '-')}")
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

package app.epistola.suite.keycloak

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.NoOpTenantProvisioner
import app.epistola.suite.tenants.TenantProvisioningPort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Keycloak implementation of [TenantProvisioningPort].
 *
 * Creates groups following the `ep_{tenantKey}_{role}` naming convention
 * for each of the four tenant roles (reader, editor, generator, manager).
 */
class KeycloakTenantProvisioner(
    private val keycloakAdminClient: KeycloakAdminClient,
) : TenantProvisioningPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun provisionTenant(tenantKey: TenantKey, tenantName: String) {
        log.info("Provisioning Keycloak groups for tenant: {}", tenantKey.value)

        for (role in TenantRole.entries) {
            val groupName = groupNameFor(tenantKey, role)
            try {
                keycloakAdminClient.createGroup(groupName)
                log.info("Created Keycloak group: {}", groupName)
            } catch (e: Exception) {
                log.warn("Failed to create Keycloak group '{}': {}", groupName, e.message)
            }
        }
    }

    override fun deprovisionTenant(tenantKey: TenantKey) {
        val prefix = "ep_${tenantKey.value}_"
        try {
            keycloakAdminClient.deleteGroupsByPrefix(prefix)
        } catch (e: Exception) {
            log.warn("Failed to delete Keycloak groups for tenant '{}': {}", tenantKey.value, e.message)
        }
    }

    companion object {
        fun groupNameFor(tenantKey: TenantKey, role: TenantRole): String = "ep_${tenantKey.value}_${role.name.lowercase()}"
    }
}

/**
 * Configuration that provides [TenantProvisioningPort] bean.
 * Uses Keycloak when available, otherwise falls back to no-op.
 */
@Configuration
class TenantProvisioningConfiguration {

    @Bean
    @ConditionalOnBean(KeycloakAdminClient::class)
    fun keycloakTenantProvisioner(keycloakAdminClient: KeycloakAdminClient): TenantProvisioningPort = KeycloakTenantProvisioner(keycloakAdminClient)

    @Bean
    @ConditionalOnMissingBean(TenantProvisioningPort::class)
    fun noOpTenantProvisioner(): TenantProvisioningPort = NoOpTenantProvisioner()
}

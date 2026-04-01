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
 * Creates hierarchical groups under `/epistola/tenants/{tenantKey}/{role}`
 * for each of the four tenant roles (reader, editor, generator, manager).
 */
class KeycloakTenantProvisioner(
    private val keycloakAdminClient: KeycloakAdminClient,
) : TenantProvisioningPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun provisionTenant(tenantKey: TenantKey, tenantName: String) {
        log.info("Provisioning Keycloak groups for tenant: {}", tenantKey.value)

        for (role in TenantRole.entries) {
            val groupPath = groupPathFor(tenantKey, role)
            try {
                keycloakAdminClient.ensureGroupPath(groupPath)
                log.info("Ensured Keycloak group path: {}", groupPath)
            } catch (e: Exception) {
                log.warn("Failed to create Keycloak group path '{}': {}", groupPath, e.message)
            }
        }
    }

    override fun deprovisionTenant(tenantKey: TenantKey) {
        val tenantGroupPath = "/epistola/tenants/${tenantKey.value}"
        try {
            val group = keycloakAdminClient.findGroupByPath(tenantGroupPath)
            if (group != null) {
                val groupId = java.util.UUID.fromString(group["id"].toString())
                keycloakAdminClient.deleteGroup(groupId)
                log.info("Deleted Keycloak tenant group: {}", tenantGroupPath)
            } else {
                log.info("Keycloak tenant group not found (already removed?): {}", tenantGroupPath)
            }
        } catch (e: Exception) {
            log.warn("Failed to delete Keycloak groups for tenant '{}': {}", tenantKey.value, e.message)
        }
    }

    companion object {
        fun groupPathFor(tenantKey: TenantKey, role: TenantRole): String = "/epistola/tenants/${tenantKey.value}/${role.name.lowercase()}"
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

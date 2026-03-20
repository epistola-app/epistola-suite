package app.epistola.suite.tenants

import app.epistola.suite.common.ids.TenantKey

/**
 * Port for provisioning tenant-related resources in external identity providers.
 *
 * Implementations create groups, organizations, or other IDP-specific resources
 * when a new tenant is created in Epistola.
 *
 * The default no-op implementation is used when no IDP admin client is configured.
 */
interface TenantProvisioningPort {

    /**
     * Provisions IDP resources for a new tenant.
     *
     * For Keycloak, this creates groups following the `ep_{tenantKey}_{role}` convention:
     * - `ep_{key}_reader`
     * - `ep_{key}_editor`
     * - `ep_{key}_generator`
     * - `ep_{key}_manager`
     *
     * Implementations should not throw on failure — tenant creation should succeed
     * even if IDP provisioning fails. Failures should be logged as warnings.
     */
    fun provisionTenant(tenantKey: TenantKey, tenantName: String)

    /**
     * Removes IDP resources for a deleted tenant.
     */
    fun deprovisionTenant(tenantKey: TenantKey)
}

/**
 * No-op implementation used when no IDP admin client is configured.
 */
class NoOpTenantProvisioner : TenantProvisioningPort {
    override fun provisionTenant(tenantKey: TenantKey, tenantName: String) {
        // No IDP configured — nothing to provision
    }

    override fun deprovisionTenant(tenantKey: TenantKey) {
        // No IDP configured — nothing to deprovision
    }
}

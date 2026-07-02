package app.epistola.suite.tenants

import app.epistola.suite.mediator.EventHandler
import app.epistola.suite.mediator.EventPhase
import app.epistola.suite.tenants.commands.CreateTenant
import org.springframework.stereotype.Component

/**
 * Provisions IDP resources (e.g. Keycloak tenant role groups) for every created tenant,
 * regardless of which surface dispatched [CreateTenant] — UI, REST API, or restore.
 *
 * AFTER_COMMIT: provisioning is external I/O and non-critical by contract
 * ([TenantProvisioningPort] implementations log failures instead of throwing) — it must
 * neither hold the command transaction open nor roll the tenant back. A missed
 * provisioning is recoverable in the IDP; a missing tenant row is not.
 */
@Component
class ProvisionIdpOnTenantCreate(
    // Nullable: the host app registers the port (Keycloak or NoOp); bare-core contexts
    // (module tests) have none, and provisioning is simply skipped there.
    private val provisioner: TenantProvisioningPort?,
) : EventHandler<CreateTenant> {
    override val phase = EventPhase.AFTER_COMMIT

    override fun on(event: CreateTenant, result: Any?) {
        provisioner?.provisionTenant(event.id, event.name)
    }
}

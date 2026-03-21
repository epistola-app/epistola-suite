package app.epistola.suite.tenants.commands

import app.epistola.suite.common.EntityIdentifiable
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Routable
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

data class DeleteTenant(
    val id: TenantKey,
) : Command<Boolean>,
    EntityIdentifiable,
    Routable,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
    override val entityId: String get() = id.value
    override val routingKey: String get() = id.value
}

@Component
class DeleteTenantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteTenant, Boolean> {
    /**
     * Deletes a tenant by ID.
     * Returns true if a tenant was deleted, false if not found.
     * Note: Due to CASCADE, this will also delete all associated templates.
     */
    @Transactional
    override fun handle(command: DeleteTenant): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        handle.createUpdate("DELETE FROM tenants WHERE id = :id")
            .bind("id", command.id)
            .execute() > 0
    }
}

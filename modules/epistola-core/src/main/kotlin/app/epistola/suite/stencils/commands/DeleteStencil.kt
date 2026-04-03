package app.epistola.suite.stencils.commands

import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Deletes a stencil and all its versions.
 * Templates that already contain copies of this stencil's content are not affected.
 * Returns true if deleted, false if not found.
 */
data class DeleteStencil(
    val id: StencilId,
) : Command<Boolean>,
    RequiresPermission {
    override val permission = Permission.STENCIL_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class DeleteStencilHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteStencil, Boolean> {
    override fun handle(command: DeleteStencil): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        val rowsDeleted = handle.createUpdate(
            """
            DELETE FROM stencils
            WHERE tenant_key = :tenantId AND id = :id
            """,
        )
            .bind("tenantId", command.id.tenantKey)
            .bind("id", command.id.key)
            .execute()

        rowsDeleted > 0
    }
}

package app.epistola.suite.stencils.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.stencils.StencilInUseException
import app.epistola.suite.stencils.queries.FindStencilUsagesHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Deletes a stencil and all its versions.
 * Throws [StencilInUseException] if any template version references this stencil.
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
    override fun handle(command: DeleteStencil): Boolean {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.inTransaction<Boolean, Exception> { handle ->
            val usages = FindStencilUsagesHandler.findStencilUsages(handle, command.id)
            if (usages.isNotEmpty()) {
                throw StencilInUseException(command.id.key, usages)
            }

            val rowsDeleted = handle.createUpdate(
                """
            DELETE FROM stencils
            WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND id = :id
            """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("id", command.id.key)
                .execute()

            rowsDeleted > 0
        }
    }
}

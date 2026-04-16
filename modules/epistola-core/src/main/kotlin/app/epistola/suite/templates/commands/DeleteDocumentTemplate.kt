package app.epistola.suite.templates.commands

import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DeleteDocumentTemplate(
    val id: TemplateId,
) : Command<Boolean>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = id.tenantKey
}

@Component
class DeleteDocumentTemplateHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteDocumentTemplate, Boolean> {
    override fun handle(command: DeleteDocumentTemplate): Boolean {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)

        return jdbi.withHandle<Boolean, Exception> { handle ->
            val rowsAffected = handle.createUpdate(
                """
                DELETE FROM document_templates
                WHERE id = :id AND tenant_key = :tenantId AND catalog_key = :catalogKey
                """,
            )
                .bind("id", command.id.key)
                .bind("tenantId", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .execute()
            rowsAffected > 0
        }
    }
}

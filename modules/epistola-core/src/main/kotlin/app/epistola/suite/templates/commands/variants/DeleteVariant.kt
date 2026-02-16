package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

data class DeleteVariant(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Command<Boolean>

@Component
class DeleteVariantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteVariant, Boolean> {
    override fun handle(command: DeleteVariant): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM template_variants
                WHERE tenant_id = :tenantId AND id = :variantId AND template_id = :templateId
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .execute()

        rowsAffected > 0
    }
}

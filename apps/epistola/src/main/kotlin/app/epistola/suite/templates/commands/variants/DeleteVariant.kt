package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
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
        // Verify the variant belongs to a template owned by the tenant before deleting
        val variantExists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM template_variants tv
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE tv.id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                """,
        )
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .bind("tenantId", command.tenantId)
            .mapTo<Boolean>()
            .one()

        if (!variantExists) {
            return@inTransaction false
        }

        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM template_variants
                WHERE id = :variantId
                """,
        )
            .bind("variantId", command.variantId)
            .execute()

        rowsAffected > 0
    }
}

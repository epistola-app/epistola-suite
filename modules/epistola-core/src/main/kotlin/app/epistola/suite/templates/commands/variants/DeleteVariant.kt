package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class DeleteVariant(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
) : Command<Boolean>

@Component
class DeleteVariantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<DeleteVariant, Boolean> {
    override fun handle(command: DeleteVariant): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        // Check if this variant is the default — block deletion if so
        val isDefault = handle.createQuery(
            """
                SELECT is_default FROM template_variants
                WHERE tenant_id = :tenantId AND id = :variantId AND template_id = :templateId
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .mapTo<Boolean>()
            .findOne()
            .orElse(null) ?: return@inTransaction false

        if (isDefault) {
            throw DefaultVariantDeletionException(command.variantId)
        }

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

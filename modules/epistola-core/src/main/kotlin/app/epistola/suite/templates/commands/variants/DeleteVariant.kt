package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class DeleteVariant(
    val variantId: VariantId,
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
                WHERE tenant_key = :tenantId AND id = :variantId AND template_key = :templateId
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("variantId", command.variantId.key)
            .bind("templateId", command.variantId.templateKey)
            .mapTo<Boolean>()
            .findOne()
            .orElse(null) ?: return@inTransaction false

        if (isDefault) {
            throw DefaultVariantDeletionException(command.variantId.key)
        }

        val rowsAffected = handle.createUpdate(
            """
                DELETE FROM template_variants
                WHERE tenant_key = :tenantId AND id = :variantId AND template_key = :templateId
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("variantId", command.variantId.key)
            .bind("templateId", command.variantId.templateKey)
            .execute()

        rowsAffected > 0
    }
}

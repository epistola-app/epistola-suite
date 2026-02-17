package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Sets a variant as the default for its template.
 * Clears the default flag on the previous default variant.
 * Returns null if the variant is not found.
 */
data class SetDefaultVariant(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Command<TemplateVariant?>

@Component
class SetDefaultVariantHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetDefaultVariant, TemplateVariant?> {
    override fun handle(command: SetDefaultVariant): TemplateVariant? = jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
        // Verify variant belongs to the template and tenant
        val exists = handle.createQuery(
            """
                SELECT EXISTS (
                    SELECT 1 FROM template_variants
                    WHERE tenant_id = :tenantId AND id = :variantId AND template_id = :templateId
                )
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .mapTo<Boolean>()
            .one()

        if (!exists) return@inTransaction null

        // Clear default on current default variant for this template
        handle.createUpdate(
            """
                UPDATE template_variants
                SET is_default = FALSE
                WHERE tenant_id = :tenantId AND template_id = :templateId AND is_default = TRUE
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("templateId", command.templateId)
            .execute()

        // Set the new default and return it
        handle.createQuery(
            """
                UPDATE template_variants
                SET is_default = TRUE
                WHERE tenant_id = :tenantId AND id = :variantId
                RETURNING *
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .mapTo<TemplateVariant>()
            .findOne()
            .orElse(null)
    }
}

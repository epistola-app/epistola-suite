package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    val variantId: VariantId,
) : Command<TemplateVariant?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

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
                    WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND id = :variantId AND template_key = :templateId
                )
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("variantId", command.variantId.key)
            .bind("templateId", command.variantId.templateKey)
            .mapTo<Boolean>()
            .one()

        if (!exists) return@inTransaction null

        // Clear default on current default variant for this template
        handle.createUpdate(
            """
                UPDATE template_variants
                SET is_default = FALSE
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND template_key = :templateId AND is_default = TRUE
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("templateId", command.variantId.templateKey)
            .execute()

        // Set the new default and return it
        handle.createQuery(
            """
                UPDATE template_variants
                SET is_default = TRUE
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND id = :variantId
                RETURNING *
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("variantId", command.variantId.key)
            .mapTo<TemplateVariant>()
            .findOne()
            .orElse(null)
    }
}

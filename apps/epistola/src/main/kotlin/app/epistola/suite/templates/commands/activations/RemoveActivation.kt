package app.epistola.suite.templates.commands.activations

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Removes an activation for a variant in an environment.
 *
 * Returns true if an activation was removed, false if:
 * - No activation existed
 * - The environment or variant doesn't belong to the tenant
 */
data class RemoveActivation(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val environmentId: EnvironmentId,
) : Command<Boolean>

@Component
class RemoveActivationHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RemoveActivation, Boolean> {
    override fun handle(command: RemoveActivation): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        // Verify environment belongs to tenant
        val environmentExists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM environments
                WHERE id = :environmentId AND tenant_id = :tenantId
                """,
        )
            .bind("environmentId", command.environmentId)
            .bind("tenantId", command.tenantId)
            .mapTo<Boolean>()
            .one()

        if (!environmentExists) {
            return@inTransaction false
        }

        // Verify variant belongs to tenant's template
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

        val rowsDeleted = handle.createUpdate(
            """
                DELETE FROM environment_activations
                WHERE environment_id = :environmentId AND variant_id = :variantId
                """,
        )
            .bind("environmentId", command.environmentId)
            .bind("variantId", command.variantId)
            .execute()

        rowsDeleted > 0
    }
}

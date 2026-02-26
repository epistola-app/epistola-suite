package app.epistola.suite.templates.commands.activations

import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
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
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val environmentId: EnvironmentKey,
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
                WHERE id = :environmentId AND tenant_key = :tenantId
                """,
        )
            .bind("environmentId", command.environmentId)
            .bind("tenantId", command.tenantId)
            .mapTo<Boolean>()
            .one()

        if (!environmentExists) {
            return@inTransaction false
        }

        val rowsDeleted = handle.createUpdate(
            """
                DELETE FROM environment_activations
                WHERE tenant_key = :tenantId AND environment_key = :environmentId AND variant_key = :variantId
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("environmentId", command.environmentId)
            .bind("variantId", command.variantId)
            .execute()

        rowsDeleted > 0
    }
}

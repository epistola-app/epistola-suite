package app.epistola.suite.templates.commands.activations

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    val variantId: VariantId,
    val environmentId: EnvironmentId,
) : Command<Boolean>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_PUBLISH
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

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
            .bind("environmentId", command.environmentId.key)
            .bind("tenantId", command.environmentId.tenantKey)
            .mapTo<Boolean>()
            .one()

        if (!environmentExists) {
            return@inTransaction false
        }

        val rowsDeleted = handle.createUpdate(
            """
                DELETE FROM environment_activations
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND environment_key = :environmentId
                  AND template_key = :templateId AND variant_key = :variantId
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("catalogKey", command.variantId.catalogKey)
            .bind("environmentId", command.environmentId.key)
            .bind("templateId", command.variantId.templateKey)
            .bind("variantId", command.variantId.key)
            .execute()

        rowsDeleted > 0
    }
}

package app.epistola.suite.templates.commands.activations

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.EnvironmentActivation
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Sets the active version for a variant in an environment.
 * Only published versions can be activated.
 *
 * Returns the activation, or null if:
 * - The environment doesn't exist or doesn't belong to the tenant
 * - The variant doesn't exist or doesn't belong to a template owned by the tenant
 * - The version doesn't exist or is not published
 */
data class SetActivation(
    val tenantId: UUID,
    val templateId: UUID,
    val variantId: UUID,
    val environmentId: UUID,
    val versionId: UUID,
) : Command<EnvironmentActivation?>

@Component
class SetActivationHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SetActivation, EnvironmentActivation?> {
    override fun handle(command: SetActivation): EnvironmentActivation? = jdbi.inTransaction<EnvironmentActivation?, Exception> { handle ->
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
            return@inTransaction null
        }

        // Verify variant and version (must be published) belong to tenant's template
        val versionValid = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM template_versions ver
                JOIN template_variants tv ON ver.variant_id = tv.id
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE ver.id = :versionId
                  AND ver.variant_id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                  AND ver.status = 'published'
                """,
        )
            .bind("versionId", command.versionId)
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .bind("tenantId", command.tenantId)
            .mapTo<Boolean>()
            .one()

        if (!versionValid) {
            return@inTransaction null
        }

        // Upsert the activation
        handle.createQuery(
            """
                INSERT INTO environment_activations (environment_id, variant_id, version_id, activated_at)
                VALUES (:environmentId, :variantId, :versionId, NOW())
                ON CONFLICT (environment_id, variant_id)
                DO UPDATE SET version_id = :versionId, activated_at = NOW()
                RETURNING *
                """,
        )
            .bind("environmentId", command.environmentId)
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .mapTo<EnvironmentActivation>()
            .one()
    }
}

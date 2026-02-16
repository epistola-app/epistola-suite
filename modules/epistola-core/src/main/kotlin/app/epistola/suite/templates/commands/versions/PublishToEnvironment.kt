package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.EnvironmentActivation
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Publishes a version to an environment in a single action.
 *
 * If the version is a draft, it freezes the content (status -> published).
 * If already published, this is a no-op on the version itself.
 * Archived versions cannot be published.
 *
 * Then creates/updates the activation for the variant in the target environment.
 *
 * Returns the result, or null if:
 * - The version doesn't exist or is archived
 * - The environment doesn't belong to the tenant
 */
data class PublishToEnvironment(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId,
    val environmentId: EnvironmentId,
) : Command<PublishToEnvironmentResult?>

data class PublishToEnvironmentResult(
    val version: TemplateVersion,
    val activation: EnvironmentActivation,
    val newDraft: TemplateVersion? = null,
)

@Component
class PublishToEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<PublishToEnvironment, PublishToEnvironmentResult?> {
    override fun handle(command: PublishToEnvironment): PublishToEnvironmentResult? = jdbi.inTransaction<PublishToEnvironmentResult?, Exception> { handle ->
        // 1. Verify environment belongs to tenant
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

        // 2. Fetch the version
        val version = handle.createQuery(
            """
                SELECT *
                FROM template_versions
                WHERE tenant_id = :tenantId AND variant_id = :variantId AND id = :versionId
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null) ?: return@inTransaction null

        // 3. Archived versions cannot be published
        if (version.status.name == "ARCHIVED") {
            return@inTransaction null
        }

        // 4. If draft, freeze it (update to published) and auto-create a new draft
        val wasDraft = version.status.name == "DRAFT"
        if (wasDraft) {
            handle.createUpdate(
                """
                    UPDATE template_versions
                    SET status = 'published', published_at = NOW()
                    WHERE tenant_id = :tenantId AND variant_id = :variantId AND id = :versionId
                    """,
            )
                .bind("tenantId", command.tenantId)
                .bind("variantId", command.variantId)
                .bind("versionId", command.versionId)
                .execute()
        }
        // If already published, no-op on version (idempotent)

        // 5. Upsert activation
        val activation = handle.createQuery(
            """
                INSERT INTO environment_activations (tenant_id, environment_id, variant_id, version_id, activated_at)
                VALUES (:tenantId, :environmentId, :variantId, :versionId, NOW())
                ON CONFLICT (tenant_id, environment_id, variant_id)
                DO UPDATE SET version_id = :versionId, activated_at = NOW()
                RETURNING *
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("environmentId", command.environmentId)
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .mapTo<EnvironmentActivation>()
            .one()

        // 6. Re-fetch version to get updated state
        val updatedVersion = handle.createQuery(
            """
                SELECT *
                FROM template_versions
                WHERE tenant_id = :tenantId AND variant_id = :variantId AND id = :versionId
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .mapTo<TemplateVersion>()
            .one()

        // 7. Auto-create a new draft if we just froze a draft, so the variant always has an editable version
        val newDraft = if (wasDraft) {
            val nextVersionId = handle.createQuery(
                """
                    SELECT COALESCE(MAX(id), 0) + 1
                    FROM template_versions
                    WHERE tenant_id = :tenantId AND variant_id = :variantId
                    """,
            )
                .bind("tenantId", command.tenantId)
                .bind("variantId", command.variantId)
                .mapTo(Int::class.java)
                .one()

            handle.createQuery(
                """
                    INSERT INTO template_versions (id, tenant_id, variant_id, template_model, status, created_at)
                    VALUES (:id, :tenantId, :variantId,
                            (SELECT template_model FROM template_versions WHERE tenant_id = :tenantId AND variant_id = :variantId AND id = :publishedId),
                            'draft', NOW())
                    RETURNING *
                    """,
            )
                .bind("id", VersionId.of(nextVersionId))
                .bind("tenantId", command.tenantId)
                .bind("variantId", command.variantId)
                .bind("publishedId", command.versionId)
                .mapTo<TemplateVersion>()
                .one()
        } else {
            null
        }

        PublishToEnvironmentResult(
            version = updatedVersion,
            activation = activation,
            newDraft = newDraft,
        )
    }
}

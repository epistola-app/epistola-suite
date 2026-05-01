package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    val versionId: VersionId,
    val environmentId: EnvironmentId,
) : Command<PublishToEnvironmentResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

data class PublishToEnvironmentResult(
    val version: TemplateVersion,
    val activation: EnvironmentActivation,
)

@Component
class PublishToEnvironmentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<PublishToEnvironment, PublishToEnvironmentResult?> {
    override fun handle(command: PublishToEnvironment): PublishToEnvironmentResult? {
        return jdbi.inTransaction<PublishToEnvironmentResult?, Exception> { handle ->
            // 1. Verify environment belongs to tenant
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
                return@inTransaction null
            }

            // 2. Publish the version (handles contract auto-publish, theme snapshot, etc.)
            val version = PublishVersion(versionId = command.versionId).execute()
                ?: return@inTransaction null

            // 3. Upsert activation
            val activation = handle.createQuery(
                """
                INSERT INTO environment_activations (tenant_key, catalog_key, environment_key, template_key, variant_key, version_key, activated_at)
                VALUES (:tenantId, :catalogKey, :environmentId, :templateId, :variantId, :versionId, NOW())
                ON CONFLICT (tenant_key, catalog_key, environment_key, template_key, variant_key)
                DO UPDATE SET version_key = :versionId, activated_at = NOW()
                RETURNING *
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("environmentId", command.environmentId.key)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<EnvironmentActivation>()
                .one()

            PublishToEnvironmentResult(
                version = version,
                activation = activation,
            )
        }
    }
}

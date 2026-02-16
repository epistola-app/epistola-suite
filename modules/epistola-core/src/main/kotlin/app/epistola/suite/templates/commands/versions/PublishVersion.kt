package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Publishes a draft version by updating its status to 'published'.
 * BREAKING CHANGE: Publishing now updates the same record instead of creating a new one.
 * The draft is NOT preserved - it becomes the published version.
 *
 * Returns the published version (same ID), or null if:
 * - The version doesn't exist
 * - The version is not a draft
 * - The tenant doesn't own the template
 */
data class PublishVersion(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId,
) : Command<TemplateVersion?>

@Component
class PublishVersionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<PublishVersion, TemplateVersion?> {
    override fun handle(command: PublishVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        // Update the draft to published status (no new record created)
        // The WHERE clause ensures the version exists, belongs to the tenant, is a draft
        val updated = handle.createUpdate(
            """
                UPDATE template_versions
                SET status = 'published',
                    published_at = NOW()
                WHERE tenant_id = :tenantId AND variant_id = :variantId
                  AND id = :versionId
                  AND status = 'draft'
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .execute()

        if (updated == 0) {
            return@inTransaction null
        }

        // Return the same version (now published)
        handle.createQuery(
            """
                SELECT *
                FROM template_versions
                WHERE tenant_id = :tenantId AND variant_id = :variantId
                  AND id = :versionId
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .mapTo<TemplateVersion>()
            .one()
    }
}

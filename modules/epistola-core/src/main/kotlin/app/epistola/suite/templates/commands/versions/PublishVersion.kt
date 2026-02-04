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
        // Verify the version exists, is a draft, and tenant owns it
        val exists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM template_versions ver
                JOIN template_variants tv ON ver.variant_id = tv.id
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE ver.id = :versionId
                  AND ver.variant_id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                  AND ver.status = 'draft'
                """,
        )
            .bind("versionId", command.versionId)
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .bind("tenantId", command.tenantId)
            .mapTo(Boolean::class.java)
            .one()

        if (!exists) {
            return@inTransaction null
        }

        // Update the draft to published status (no new record created)
        val updated = handle.createUpdate(
            """
                UPDATE template_versions
                SET status = 'published',
                    published_at = NOW()
                WHERE variant_id = :variantId
                  AND id = :versionId
                  AND status = 'draft'
                """,
        )
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
                WHERE variant_id = :variantId
                  AND id = :versionId
                """,
        )
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .mapTo<TemplateVersion>()
            .one()
    }
}

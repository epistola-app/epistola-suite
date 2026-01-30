package app.epistola.suite.templates.commands.versions

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Publishes a draft version by creating a new immutable published version.
 * The draft is preserved for continued editing.
 *
 * Returns the newly created published version, or null if:
 * - The version doesn't exist
 * - The version is not a draft
 * - The tenant doesn't own the template
 */
data class PublishVersion(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val versionId: Long,
) : Command<TemplateVersion?>

@Component
class PublishVersionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<PublishVersion, TemplateVersion?> {
    override fun handle(command: PublishVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        // Get the draft version and verify ownership
        val draft = handle.createQuery(
            """
                SELECT ver.id, ver.variant_id, ver.version_number, ver.template_model, ver.status, ver.created_at, ver.published_at, ver.archived_at
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
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)

        if (draft == null) {
            return@inTransaction null
        }

        // Get the next version number for this variant
        val nextVersionNumber = handle.createQuery(
            """
                SELECT COALESCE(MAX(version_number), 0) + 1
                FROM template_versions
                WHERE variant_id = :variantId AND version_number IS NOT NULL
                """,
        )
            .bind("variantId", command.variantId)
            .mapTo<Int>()
            .one()

        // Create a new published version with the draft's content
        handle.createQuery(
            """
                INSERT INTO template_versions (variant_id, version_number, template_model, status, created_at, published_at)
                SELECT :variantId, :versionNumber, template_model, 'published', NOW(), NOW()
                FROM template_versions
                WHERE id = :draftId
                RETURNING *
                """,
        )
            .bind("variantId", command.variantId)
            .bind("versionNumber", nextVersionNumber)
            .bind("draftId", draft.id)
            .mapTo<TemplateVersion>()
            .one()
    }
}

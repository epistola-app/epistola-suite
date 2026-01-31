package app.epistola.suite.templates.commands.versions

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Archives a published version.
 * Only published versions can be archived.
 * Archived versions are immutable and kept for historical purposes.
 *
 * Returns the archived version, or null if:
 * - The version doesn't exist
 * - The version is not published (drafts and already archived versions cannot be archived)
 * - The tenant doesn't own the template
 */
data class ArchiveVersion(
    val tenantId: UUID,
    val templateId: UUID,
    val variantId: UUID,
    val versionId: UUID,
) : Command<TemplateVersion?>

@Component
class ArchiveVersionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ArchiveVersion, TemplateVersion?> {
    override fun handle(command: ArchiveVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        // Verify the version exists, is published, and belongs to tenant
        val isPublished = handle.createQuery(
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

        if (!isPublished) {
            return@inTransaction null
        }

        // Archive the version
        handle.createQuery(
            """
                UPDATE template_versions
                SET status = 'archived', archived_at = NOW()
                WHERE id = :versionId
                RETURNING *
                """,
        )
            .bind("versionId", command.versionId)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}

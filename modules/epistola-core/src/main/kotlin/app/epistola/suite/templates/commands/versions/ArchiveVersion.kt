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
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId,
) : Command<TemplateVersion?>

@Component
class ArchiveVersionHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ArchiveVersion, TemplateVersion?> {
    override fun handle(command: ArchiveVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        // Archive the version (only if it's published and belongs to tenant)
        handle.createQuery(
            """
                UPDATE template_versions
                SET status = 'archived', archived_at = NOW()
                WHERE tenant_id = :tenantId AND variant_id = :variantId AND id = :versionId
                  AND status = 'published'
                RETURNING *
                """,
        )
            .bind("tenantId", command.tenantId)
            .bind("variantId", command.variantId)
            .bind("versionId", command.versionId)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}

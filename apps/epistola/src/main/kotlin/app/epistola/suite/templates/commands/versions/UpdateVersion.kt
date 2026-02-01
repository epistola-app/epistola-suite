package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateModel
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Updates a draft version's content.
 * Only draft versions can be updated; published/archived versions are immutable.
 */
data class UpdateVersion(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId,
    val templateModel: TemplateModel?,
) : Command<TemplateVersion?>

@Component
class UpdateVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateVersion, TemplateVersion?> {
    override fun handle(command: UpdateVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        // Verify ownership and that version is a draft
        val isDraft = handle.createQuery(
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
            .mapTo<Boolean>()
            .one()

        if (!isDraft) {
            return@inTransaction null
        }

        val templateModelJson = command.templateModel?.let { objectMapper.writeValueAsString(it) }

        handle.createQuery(
            """
                UPDATE template_versions
                SET template_model = :templateModel::jsonb
                WHERE id = :versionId AND status = 'draft'
                RETURNING *
                """,
        )
            .bind("versionId", command.versionId)
            .bind("templateModel", templateModelJson)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}

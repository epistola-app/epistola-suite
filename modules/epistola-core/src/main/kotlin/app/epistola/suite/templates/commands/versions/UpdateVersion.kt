package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateDocument
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
    val versionId: VersionId,
    val templateModel: TemplateDocument,
) : Command<TemplateVersion?>

@Component
class UpdateVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateVersion, TemplateVersion?> {
    override fun handle(command: UpdateVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        val templateModelJson = objectMapper.writeValueAsString(command.templateModel)

        // Update the draft (WHERE clause ensures ownership and draft status)
        handle.createQuery(
            """
                UPDATE template_versions
                SET template_model = :templateModel::jsonb
                WHERE tenant_key = :tenantId AND variant_key = :variantId AND id = :versionId
                  AND status = 'draft'
                RETURNING *
                """,
        )
            .bind("tenantId", command.versionId.tenantKey)
            .bind("variantId", command.versionId.variantKey)
            .bind("versionId", command.versionId.key)
            .bind("templateModel", templateModelJson)
            .mapTo<TemplateVersion>()
            .findOne()
            .orElse(null)
    }
}

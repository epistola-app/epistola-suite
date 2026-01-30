package app.epistola.suite.templates.commands.versions

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateModel
import app.epistola.suite.templates.model.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Creates or updates the draft version for a variant.
 * If no draft exists, creates one. If a draft exists, updates it.
 */
data class UpdateDraft(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val templateModel: TemplateModel?,
) : Command<TemplateVersion?>

@Component
class UpdateDraftHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateDraft, TemplateVersion?> {
    override fun handle(command: UpdateDraft): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        // Verify the variant belongs to a template owned by the tenant
        val variantExists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM template_variants tv
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE tv.id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                """,
        )
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .bind("tenantId", command.tenantId)
            .mapTo<Boolean>()
            .one()

        if (!variantExists) {
            return@inTransaction null
        }

        val templateModelJson = command.templateModel?.let { objectMapper.writeValueAsString(it) }

        // Use upsert to create or update draft
        handle.createQuery(
            """
                INSERT INTO template_versions (variant_id, version_number, template_model, status, created_at)
                VALUES (:variantId, NULL, :templateModel::jsonb, 'draft', NOW())
                ON CONFLICT (variant_id) WHERE status = 'draft'
                DO UPDATE SET template_model = :templateModel::jsonb
                RETURNING *
                """,
        )
            .bind("variantId", command.variantId)
            .bind("templateModel", templateModelJson)
            .mapTo<TemplateVersion>()
            .one()
    }
}

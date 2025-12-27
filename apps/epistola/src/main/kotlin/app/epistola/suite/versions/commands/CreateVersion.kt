package app.epistola.suite.versions.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateModel
import app.epistola.suite.versions.TemplateVersion
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Creates a new draft version for a variant.
 * Returns null if variant doesn't exist or tenant doesn't own the template.
 * Throws exception if a draft already exists for this variant.
 */
data class CreateVersion(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val templateModel: TemplateModel? = null,
) : Command<TemplateVersion?>

@Component
class CreateVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateVersion, TemplateVersion?> {
    override fun handle(command: CreateVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
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

        handle.createQuery(
            """
                INSERT INTO template_versions (variant_id, version_number, template_model, status, created_at)
                VALUES (:variantId, NULL, :templateModel::jsonb, 'draft', NOW())
                RETURNING *
                """,
        )
            .bind("variantId", command.variantId)
            .bind("templateModel", templateModelJson)
            .mapTo<TemplateVersion>()
            .one()
    }
}

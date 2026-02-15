package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
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
 * Creates or updates the draft version for a variant.
 * If no draft exists, creates one. If a draft exists, updates it.
 */
data class UpdateDraft(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val templateModel: TemplateDocument,
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

        val templateModelJson = objectMapper.writeValueAsString(command.templateModel)

        // Try to update existing draft first
        val updated = handle.createUpdate(
            """
                UPDATE template_versions
                SET template_model = :templateModel::jsonb
                WHERE variant_id = :variantId
                  AND status = 'draft'
                """,
        )
            .bind("variantId", command.variantId)
            .bind("templateModel", templateModelJson)
            .execute()

        if (updated > 0) {
            // Draft existed and was updated - return it
            return@inTransaction handle.createQuery(
                """
                    SELECT *
                    FROM template_versions
                    WHERE variant_id = :variantId
                      AND status = 'draft'
                    """,
            )
                .bind("variantId", command.variantId)
                .mapTo<TemplateVersion>()
                .one()
        }

        // No draft exists - create new one
        // Calculate next version ID for this variant
        val nextVersionId = handle.createQuery(
            """
                SELECT COALESCE(MAX(id), 0) + 1 as next_id
                FROM template_versions
                WHERE variant_id = :variantId
                """,
        )
            .bind("variantId", command.variantId)
            .mapTo(Int::class.java)
            .one()

        // Enforce max version limit
        require(nextVersionId <= VersionId.MAX_VERSION) {
            "Maximum version limit (${VersionId.MAX_VERSION}) reached for variant ${command.variantId}"
        }

        val versionId = VersionId.of(nextVersionId)

        handle.createQuery(
            """
                INSERT INTO template_versions (id, variant_id, template_model, status, created_at)
                VALUES (:id, :variantId, :templateModel::jsonb, 'draft', NOW())
                RETURNING *
                """,
        )
            .bind("id", versionId)
            .bind("variantId", command.variantId)
            .bind("templateModel", templateModelJson)
            .mapTo<TemplateVersion>()
            .one()
    }
}

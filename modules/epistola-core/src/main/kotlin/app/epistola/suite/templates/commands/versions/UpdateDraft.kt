package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
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
                FROM template_variants
                WHERE tenant_key = :tenantId AND id = :variantId AND template_key = :templateId
                """,
        )
            .bind("variantId", command.variantId.key)
            .bind("templateId", command.variantId.templateKey)
            .bind("tenantId", command.variantId.tenantKey)
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
                WHERE tenant_key = :tenantId AND variant_key = :variantId
                  AND status = 'draft'
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("variantId", command.variantId.key)
            .bind("templateModel", templateModelJson)
            .execute()

        if (updated > 0) {
            // Draft existed and was updated - return it
            return@inTransaction handle.createQuery(
                """
                    SELECT *
                    FROM template_versions
                    WHERE tenant_key = :tenantId AND variant_key = :variantId
                      AND status = 'draft'
                    """,
            )
                .bind("tenantId", command.variantId.tenantKey)
                .bind("variantId", command.variantId.key)
                .mapTo<TemplateVersion>()
                .one()
        }

        // No draft exists - create new one
        // Calculate next version ID for this variant
        val nextVersionId = handle.createQuery(
            """
                SELECT COALESCE(MAX(id), 0) + 1 as next_id
                FROM template_versions
                WHERE tenant_key = :tenantId AND variant_key = :variantId
                """,
        )
            .bind("tenantId", command.variantId.tenantKey)
            .bind("variantId", command.variantId.key)
            .mapTo(Int::class.java)
            .one()

        // Enforce max version limit
        require(nextVersionId <= VersionKey.MAX_VERSION) {
            "Maximum version limit (${VersionKey.MAX_VERSION}) reached for variant ${command.variantId.key}"
        }

        val versionId = VersionKey.of(nextVersionId)

        handle.createQuery(
            """
                INSERT INTO template_versions (id, tenant_key, variant_key, template_model, status, created_at)
                VALUES (:id, :tenantId, :variantId, :templateModel::jsonb, 'draft', NOW())
                RETURNING *
                """,
        )
            .bind("id", versionId)
            .bind("tenantId", command.variantId.tenantKey)
            .bind("variantId", command.variantId.key)
            .bind("templateModel", templateModelJson)
            .mapTo<TemplateVersion>()
            .one()
    }
}

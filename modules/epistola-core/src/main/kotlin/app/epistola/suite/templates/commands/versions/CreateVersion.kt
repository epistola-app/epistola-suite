package app.epistola.suite.templates.commands.versions

import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.model.createDefaultTemplateModel
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Creates a new draft version for a variant.
 * Version ID is calculated automatically as MAX(id) + 1 for the variant.
 * If no templateModel is provided, creates a default empty template structure.
 * Returns null if variant doesn't exist or tenant doesn't own the template.
 * If a draft already exists for this variant, returns the existing draft (idempotent).
 * Throws exception if maximum version limit (200) is reached.
 */
data class CreateVersion(
    val variantId: VariantId,
    val templateModel: TemplateDocument? = null,
) : Command<TemplateVersion?>

@Component
class CreateVersionHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateVersion, TemplateVersion?> {
    override fun handle(command: CreateVersion): TemplateVersion? = jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
        // Verify the variant exists and get template name for default model
        val templateInfo = handle.createQuery(
            """
                SELECT dt.name as template_name
                FROM template_variants tv
                JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.id = tv.template_key
                WHERE tv.tenant_key = :tenantId AND tv.id = :variantId
                  AND tv.template_key = :templateId
                """,
        )
            .bind("variantId", command.variantId.key)
            .bind("templateId", command.variantId.templateKey)
            .bind("tenantId", command.variantId.tenantKey)
            .mapToMap()
            .findOne()
            .orElse(null) ?: return@inTransaction null

        val templateName = templateInfo["template_name"] as String

        // Check if a draft already exists for this variant (idempotent behavior)
        val existingDraft = handle.createQuery(
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
            .findOne()
            .orElse(null)

        // If draft exists, return it (idempotent - safe to call multiple times)
        if (existingDraft != null) {
            return@inTransaction existingDraft
        }

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

        // Use provided model or create default empty template structure
        val modelToSave = command.templateModel ?: createDefaultTemplateModel(templateName, command.variantId.key)
        val templateModelJson = objectMapper.writeValueAsString(modelToSave)

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

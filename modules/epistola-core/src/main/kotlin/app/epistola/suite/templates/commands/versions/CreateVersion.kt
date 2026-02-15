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
 * Creates a new draft version for a variant.
 * Version ID is calculated automatically as MAX(id) + 1 for the variant.
 * If no templateModel is provided, creates a default empty template structure.
 * Returns null if variant doesn't exist or tenant doesn't own the template.
 * If a draft already exists for this variant, returns the existing draft (idempotent).
 * Throws exception if maximum version limit (200) is reached.
 */
data class CreateVersion(
    val tenantId: TenantId,
    val templateId: TemplateId,
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
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE tv.id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
                """,
        )
            .bind("variantId", command.variantId)
            .bind("templateId", command.templateId)
            .bind("tenantId", command.tenantId)
            .mapToMap()
            .findOne()
            .orElse(null) ?: return@inTransaction null

        val templateName = templateInfo["template_name"] as String

        // Check if a draft already exists for this variant (idempotent behavior)
        val existingDraft = handle.createQuery(
            """
                SELECT *
                FROM template_versions
                WHERE variant_id = :variantId
                  AND status = 'draft'
                """,
        )
            .bind("variantId", command.variantId)
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

        // Use provided model or create default empty template structure
        val modelToSave = command.templateModel ?: createDefaultTemplateModel(templateName, command.variantId)
        val templateModelJson = objectMapper.writeValueAsString(modelToSave)

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

    private fun createDefaultTemplateModel(templateName: String, variantId: VariantId): Map<String, Any> {
        val rootId = "root-${variantId.value}"
        val slotId = "slot-${variantId.value}"
        return mapOf(
            "modelVersion" to 1,
            "root" to rootId,
            "nodes" to mapOf(
                rootId to mapOf(
                    "id" to rootId,
                    "type" to "root",
                    "slots" to listOf(slotId),
                ),
            ),
            "slots" to mapOf(
                slotId to mapOf(
                    "id" to slotId,
                    "nodeId" to rootId,
                    "name" to "children",
                    "children" to emptyList<String>(),
                ),
            ),
            "themeRef" to mapOf("type" to "inherit"),
        )
    }
}

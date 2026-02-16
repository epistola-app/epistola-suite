package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateVariant(
    val id: VariantId,
    val tenantId: TenantId,
    val templateId: TemplateId,
    val title: String?,
    val description: String?,
    val attributes: Map<String, String> = emptyMap(),
) : Command<TemplateVariant?>

@Component
class CreateVariantHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateVariant, TemplateVariant?> {
    override fun handle(command: CreateVariant): TemplateVariant? {
        // Validate attributes against the tenant's attribute definitions
        validateAttributes(command.tenantId, command.attributes)

        return jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
            // Verify the template belongs to the tenant and get its name for the default model
            val templateName = handle.createQuery(
                """
                SELECT name
                FROM document_templates
                WHERE id = :templateId AND tenant_id = :tenantId
                """,
            )
                .bind("templateId", command.templateId)
                .bind("tenantId", command.tenantId)
                .mapTo<String>()
                .findOne()
                .orElse(null) ?: return@inTransaction null

            val attributesJson = objectMapper.writeValueAsString(command.attributes)

            val variant = handle.createQuery(
                """
                INSERT INTO template_variants (id, tenant_id, template_id, title, description, attributes, created_at, last_modified)
                VALUES (:id, :tenantId, :templateId, :title, :description, :attributes::jsonb, NOW(), NOW())
                RETURNING *
                """,
            )
                .bind("id", command.id)
                .bind("tenantId", command.tenantId)
                .bind("templateId", command.templateId)
                .bind("title", command.title)
                .bind("description", command.description)
                .bind("attributes", attributesJson)
                .mapTo<TemplateVariant>()
                .one()

            // Create an initial draft version for the new variant with default template model (version ID = 1)
            val versionId = VersionId.of(1) // First version is always 1
            val defaultModel = createDefaultTemplateModel(templateName, variant.id)
            val templateModelJson = objectMapper.writeValueAsString(defaultModel)

            handle.createUpdate(
                """
                INSERT INTO template_versions (id, tenant_id, variant_id, template_model, status, created_at)
                VALUES (:id, :tenantId, :variantId, :templateModel::jsonb, 'draft', NOW())
                """,
            )
                .bind("id", versionId)
                .bind("tenantId", command.tenantId)
                .bind("variantId", variant.id)
                .bind("templateModel", templateModelJson)
                .execute()

            variant
        }
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

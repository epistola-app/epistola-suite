package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.createDefaultTemplateModel
import app.epistola.suite.validation.executeOrThrowDuplicate
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

        return executeOrThrowDuplicate("variant", command.id.value) {
            jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
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

                // Auto-default: first variant for a template becomes the default
                val existingCount = handle.createQuery(
                    """
                SELECT COUNT(*) FROM template_variants
                WHERE tenant_id = :tenantId AND template_id = :templateId
                """,
                )
                    .bind("tenantId", command.tenantId)
                    .bind("templateId", command.templateId)
                    .mapTo<Long>()
                    .one()
                val isDefault = existingCount == 0L

                val variant = handle.createQuery(
                    """
                INSERT INTO template_variants (id, tenant_id, template_id, title, description, attributes, is_default, created_at, last_modified)
                VALUES (:id, :tenantId, :templateId, :title, :description, :attributes::jsonb, :isDefault, NOW(), NOW())
                RETURNING *
                """,
                )
                    .bind("id", command.id)
                    .bind("tenantId", command.tenantId)
                    .bind("templateId", command.templateId)
                    .bind("title", command.title)
                    .bind("description", command.description)
                    .bind("attributes", attributesJson)
                    .bind("isDefault", isDefault)
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
    }
}

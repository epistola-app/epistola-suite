package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionKey
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
        validateAttributes(command.id.tenantKey, command.attributes)

        return executeOrThrowDuplicate("variant", command.id.key.value) {
            jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
                // Verify the template belongs to the tenant and get its name for the default model
                val templateName = handle.createQuery(
                    """
                SELECT name
                FROM document_templates
                WHERE id = :templateId AND tenant_key = :tenantId
                """,
                )
                    .bind("templateId", command.id.templateKey)
                    .bind("tenantId", command.id.tenantKey)
                    .mapTo<String>()
                    .findOne()
                    .orElse(null) ?: return@inTransaction null

                val attributesJson = objectMapper.writeValueAsString(command.attributes)

                // Auto-default: first variant for a template becomes the default
                val existingCount = handle.createQuery(
                    """
                SELECT COUNT(*) FROM template_variants
                WHERE tenant_key = :tenantId AND template_key = :templateId
                """,
                )
                    .bind("tenantId", command.id.tenantKey)
                    .bind("templateId", command.id.templateKey)
                    .mapTo<Long>()
                    .one()
                val isDefault = existingCount == 0L

                val variant = handle.createQuery(
                    """
                INSERT INTO template_variants (id, tenant_key, template_key, title, description, attributes, is_default, created_at, last_modified)
                VALUES (:id, :tenantId, :templateId, :title, :description, :attributes::jsonb, :isDefault, NOW(), NOW())
                RETURNING *
                """,
                )
                    .bind("id", command.id.key)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("templateId", command.id.templateKey)
                    .bind("title", command.title)
                    .bind("description", command.description)
                    .bind("attributes", attributesJson)
                    .bind("isDefault", isDefault)
                    .mapTo<TemplateVariant>()
                    .one()

                // Create an initial draft version for the new variant with default template model (version ID = 1)
                val versionId = VersionKey.of(1) // First version is always 1
                val defaultModel = createDefaultTemplateModel(templateName, variant.id)
                val templateModelJson = objectMapper.writeValueAsString(defaultModel)

                handle.createUpdate(
                    """
                INSERT INTO template_versions (id, tenant_key, variant_key, template_model, status, created_at)
                VALUES (:id, :tenantId, :variantId, :templateModel::jsonb, 'draft', NOW())
                """,
                )
                    .bind("id", versionId)
                    .bind("tenantId", command.id.tenantKey)
                    .bind("variantId", command.id.key)
                    .bind("templateModel", templateModelJson)
                    .execute()

                variant
            }
        }
    }
}

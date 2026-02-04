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
    val tags: Map<String, String> = emptyMap(),
) : Command<TemplateVariant?>

@Component
class CreateVariantHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<CreateVariant, TemplateVariant?> {
    override fun handle(command: CreateVariant): TemplateVariant? = jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
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

        val tagsJson = objectMapper.writeValueAsString(command.tags)

        val variant = handle.createQuery(
            """
                INSERT INTO template_variants (id, template_id, title, description, tags, created_at, last_modified)
                VALUES (:id, :templateId, :title, :description, :tags::jsonb, NOW(), NOW())
                RETURNING *
                """,
        )
            .bind("id", command.id)
            .bind("templateId", command.templateId)
            .bind("title", command.title)
            .bind("description", command.description)
            .bind("tags", tagsJson)
            .mapTo<TemplateVariant>()
            .one()

        // Create an initial draft version for the new variant with default template model (version ID = 1)
        val versionId = VersionId.of(1) // First version is always 1
        val defaultModel = createDefaultTemplateModel(templateName, variant.id)
        val templateModelJson = objectMapper.writeValueAsString(defaultModel)

        handle.createUpdate(
            """
                INSERT INTO template_versions (id, variant_id, template_model, status, created_at)
                VALUES (:id, :variantId, :templateModel::jsonb, 'draft', NOW())
                """,
        )
            .bind("id", versionId)
            .bind("variantId", variant.id)
            .bind("templateModel", templateModelJson)
            .execute()

        variant
    }

    private fun createDefaultTemplateModel(templateName: String, variantId: VariantId): Map<String, Any> = mapOf(
        "id" to "template-${variantId.value}",
        "name" to templateName,
        "version" to 1,
        "pageSettings" to mapOf(
            "format" to "A4",
            "orientation" to "portrait",
            "margins" to mapOf(
                "top" to 20,
                "right" to 20,
                "bottom" to 20,
                "left" to 20,
            ),
        ),
        "documentStyles" to mapOf<String, Any>(),
        "blocks" to emptyList<Any>(),
    )
}

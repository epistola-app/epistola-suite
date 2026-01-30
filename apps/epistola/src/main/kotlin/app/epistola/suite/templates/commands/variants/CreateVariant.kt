package app.epistola.suite.templates.commands.variants

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class CreateVariant(
    val tenantId: Long,
    val templateId: Long,
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
        // Verify the template belongs to the tenant
        val templateExists = handle.createQuery(
            """
                SELECT COUNT(*) > 0
                FROM document_templates
                WHERE id = :templateId AND tenant_id = :tenantId
                """,
        )
            .bind("templateId", command.templateId)
            .bind("tenantId", command.tenantId)
            .mapTo<Boolean>()
            .one()

        if (!templateExists) {
            return@inTransaction null
        }

        val tagsJson = objectMapper.writeValueAsString(command.tags)

        val variant = handle.createQuery(
            """
                INSERT INTO template_variants (template_id, title, description, tags, created_at, last_modified)
                VALUES (:templateId, :title, :description, :tags::jsonb, NOW(), NOW())
                RETURNING *
                """,
        )
            .bind("templateId", command.templateId)
            .bind("title", command.title)
            .bind("description", command.description)
            .bind("tags", tagsJson)
            .mapTo<TemplateVariant>()
            .one()

        // Create an initial draft version for the new variant
        handle.createUpdate(
            """
                INSERT INTO template_versions (variant_id, version_number, template_model, status, created_at)
                VALUES (:variantId, NULL, NULL, 'draft', NOW())
                """,
        )
            .bind("variantId", variant.id)
            .execute()

        variant
    }
}

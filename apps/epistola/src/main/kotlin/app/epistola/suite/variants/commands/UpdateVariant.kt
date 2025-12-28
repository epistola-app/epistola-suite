package app.epistola.suite.variants.commands

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.variants.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class UpdateVariant(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val tags: Map<String, String>,
) : Command<TemplateVariant?>

@Component
class UpdateVariantHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateVariant, TemplateVariant?> {
    override fun handle(command: UpdateVariant): TemplateVariant? = jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
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

        val tagsJson = objectMapper.writeValueAsString(command.tags)

        handle.createQuery(
            """
                UPDATE template_variants
                SET tags = :tags::jsonb, last_modified = NOW()
                WHERE id = :variantId
                RETURNING *
                """,
        )
            .bind("variantId", command.variantId)
            .bind("tags", tagsJson)
            .mapTo<TemplateVariant>()
            .findOne()
            .orElse(null)
    }
}

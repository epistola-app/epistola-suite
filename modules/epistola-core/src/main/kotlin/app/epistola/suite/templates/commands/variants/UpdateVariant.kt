package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class UpdateVariant(
    val variantId: VariantId,
    val title: String?,
    val attributes: Map<String, String>,
) : Command<TemplateVariant?>

@Component
class UpdateVariantHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : CommandHandler<UpdateVariant, TemplateVariant?> {
    override fun handle(command: UpdateVariant): TemplateVariant? {
        // Validate attributes against the tenant's attribute definitions
        validateAttributes(command.variantId.tenantKey, command.attributes)

        return jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
            val attributesJson = objectMapper.writeValueAsString(command.attributes)

            handle.createQuery(
                """
                UPDATE template_variants
                SET title = :title, attributes = :attributes::jsonb, last_modified = NOW()
                WHERE tenant_key = :tenantId AND id = :variantId AND template_key = :templateId
                RETURNING *
                """,
            )
                .bind("tenantId", command.variantId.tenantKey)
                .bind("variantId", command.variantId.key)
                .bind("templateId", command.variantId.templateKey)
                .bind("title", command.title)
                .bind("attributes", attributesJson)
                .mapTo<TemplateVariant>()
                .findOne()
                .orElse(null)
        }
    }
}

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.templates.model.TemplateVariant
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

data class UpdateVariant(
    val tenantId: TenantId,
    val templateId: TemplateId,
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
        validateAttributes(command.tenantId, command.attributes)

        return jdbi.inTransaction<TemplateVariant?, Exception> { handle ->
            val attributesJson = objectMapper.writeValueAsString(command.attributes)

            handle.createQuery(
                """
                UPDATE template_variants
                SET title = :title, attributes = :attributes::jsonb, last_modified = NOW()
                WHERE tenant_id = :tenantId AND id = :variantId AND template_id = :templateId
                RETURNING *
                """,
            )
                .bind("tenantId", command.tenantId)
                .bind("variantId", command.variantId)
                .bind("templateId", command.templateId)
                .bind("title", command.title)
                .bind("attributes", attributesJson)
                .mapTo<TemplateVariant>()
                .findOne()
                .orElse(null)
        }
    }
}

package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.DataExample
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/**
 * All context needed to render the template editor.
 */
data class EditorContext(
    val templateName: String,
    val variantAttributes: Map<String, String>,
    val templateModel: TemplateDocument,
    val dataExamples: List<DataExample>,
    val dataModel: ObjectNode?,
)

/**
 * Gets all context needed for the template editor in a single query.
 * This avoids multiple round-trips to the database.
 */
data class GetEditorContext(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Query<EditorContext?>

@Component
class GetEditorContextHandler(
    private val jdbi: Jdbi,
    private val objectMapper: ObjectMapper,
) : QueryHandler<GetEditorContext, EditorContext?> {
    override fun handle(query: GetEditorContext): EditorContext? = jdbi.withHandle<EditorContext?, Exception> { handle ->
        val row = handle.createQuery(
            """
            SELECT
                dt.name as template_name,
                dt.data_model,
                dt.data_examples,
                tv.attributes as variant_attributes,
                ver.template_model as draft_template_model
            FROM template_variants tv
            JOIN document_templates dt ON dt.tenant_id = tv.tenant_id AND dt.id = tv.template_id
            LEFT JOIN template_versions ver ON ver.tenant_id = tv.tenant_id AND ver.variant_id = tv.id AND ver.status = 'draft'
            WHERE tv.template_id = :templateId
              AND tv.tenant_id = :tenantId
              AND tv.id = :variantId
            """,
        )
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .bind("variantId", query.variantId)
            .mapToMap()
            .findOne()
            .orElse(null) ?: return@withHandle null

        // Deserialize data_examples from JSONB (comes as String or PGobject from mapToMap)
        val dataExamples: List<DataExample> = row["data_examples"]?.let { raw ->
            val json = raw.toString()
            if (json.isNotBlank() && json != "null") {
                try {
                    val typeRef = object : tools.jackson.core.type.TypeReference<List<DataExample>>() {}
                    objectMapper.readValue(json, typeRef)
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        } ?: emptyList()

        // Deserialize variant_attributes from JSONB (comes as PGobject from mapToMap)
        val variantAttributes: Map<String, String> = row["variant_attributes"]?.let { raw ->
            val json = raw.toString()
            if (json.isNotBlank() && json != "null") {
                try {
                    val typeRef = object : tools.jackson.core.type.TypeReference<Map<String, String>>() {}
                    objectMapper.readValue(json, typeRef)
                } catch (e: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
        } ?: emptyMap()

        // Deserialize draft_template_model from JSONB
        val templateModel = row["draft_template_model"]?.let { raw ->
            val json = raw.toString()
            if (json.isNotBlank()) {
                objectMapper.readValue(json, TemplateDocument::class.java)
            } else {
                null
            }
        } ?: error("Variant ${query.variantId} has no draft with templateModel - data integrity issue")

        EditorContext(
            templateName = row["template_name"] as String,
            variantAttributes = variantAttributes,
            templateModel = templateModel,
            dataExamples = dataExamples,
            dataModel = row["data_model"] as? ObjectNode,
        )
    }
}

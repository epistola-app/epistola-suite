package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    val variantId: VariantId,
) : Query<EditorContext?>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

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
                COALESCE(dt.draft_data_model, dt.data_model) as data_model,
                COALESCE(dt.draft_data_examples, dt.data_examples) as data_examples,
                tv.attributes as variant_attributes,
                COALESCE(draft.template_model, published.template_model) as draft_template_model
            FROM template_variants tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.catalog_key = tv.catalog_key AND dt.id = tv.template_key
            LEFT JOIN template_versions draft ON draft.tenant_key = tv.tenant_key AND draft.catalog_key = tv.catalog_key AND draft.template_key = tv.template_key AND draft.variant_key = tv.id AND draft.status = 'draft'
            LEFT JOIN LATERAL (
                SELECT template_model FROM template_versions
                WHERE tenant_key = tv.tenant_key AND catalog_key = tv.catalog_key AND template_key = tv.template_key AND variant_key = tv.id AND status = 'published'
                ORDER BY id DESC LIMIT 1
            ) published ON draft.template_model IS NULL
            WHERE tv.template_key = :templateId
              AND tv.tenant_key = :tenantId
              AND tv.catalog_key = :catalogKey
              AND tv.id = :variantId
            """,
        )
            .bind("templateId", query.variantId.templateKey)
            .bind("tenantId", query.variantId.tenantKey)
            .bind("catalogKey", query.variantId.catalogKey)
            .bind("variantId", query.variantId.key)
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
        } ?: error("Variant ${query.variantId} has no draft or published version with templateModel")

        EditorContext(
            templateName = row["template_name"] as String,
            variantAttributes = variantAttributes,
            templateModel = templateModel,
            dataExamples = dataExamples,
            dataModel = row["data_model"]?.let { raw ->
                val json = raw.toString()
                if (json.isNotBlank() && json != "null") {
                    try {
                        objectMapper.readValue(json, ObjectNode::class.java)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    null
                }
            },
        )
    }
}

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
 * Summary of a theme for editor dropdown display.
 */
data class ThemeSummary(
    val id: String,
    val name: String,
    val description: String?,
)

/**
 * All context needed to render the template editor.
 */
data class EditorContext(
    val templateName: String,
    val variantTags: Map<String, String>,
    val templateModel: TemplateDocument,
    val dataExamples: List<DataExample>,
    val dataModel: ObjectNode?,
    val themes: List<ThemeSummary>,
    val defaultTheme: ThemeSummary?, // resolved effective theme (template ?? tenant)
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
        // Single query to get template, variant, draft, and default theme info
        val row = handle.createQuery(
            """
            SELECT
                dt.name as template_name,
                dt.data_model,
                dt.data_examples,
                dt.theme_id as template_theme_id,
                tv.tags as variant_tags,
                ver.template_model as draft_template_model,
                theme.id as default_theme_id,
                theme.name as default_theme_name,
                theme.description as default_theme_description,
                t.default_theme_id as tenant_default_theme_id,
                tenant_theme.name as tenant_default_theme_name,
                tenant_theme.description as tenant_default_theme_description
            FROM document_templates dt
            JOIN tenants t ON t.id = dt.tenant_id
            JOIN template_variants tv ON tv.template_id = dt.id
            LEFT JOIN template_versions ver ON ver.variant_id = tv.id AND ver.status = 'draft'
            LEFT JOIN themes theme ON theme.id = dt.theme_id
            LEFT JOIN themes tenant_theme ON tenant_theme.id = t.default_theme_id
            WHERE dt.id = :templateId
              AND dt.tenant_id = :tenantId
              AND tv.id = :variantId
            """,
        )
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .bind("variantId", query.variantId)
            .mapToMap()
            .findOne()
            .orElse(null) ?: return@withHandle null

        // Get all themes for the tenant (separate query for simplicity)
        val themes = handle.createQuery(
            """
            SELECT id, name, description
            FROM themes
            WHERE tenant_id = :tenantId
            ORDER BY name ASC
            """,
        )
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                ThemeSummary(
                    id = rs.getString("id"),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                )
            }
            .list()

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

        // Parse variant tags
        @Suppress("UNCHECKED_CAST")
        val variantTags: Map<String, String> = (row["variant_tags"] as? Map<String, String>) ?: emptyMap()

        // Build template theme summary if template has one
        val templateTheme = (row["default_theme_id"] as? String)?.let { themeId ->
            ThemeSummary(
                id = themeId,
                name = row["default_theme_name"] as String,
                description = row["default_theme_description"] as? String,
            )
        }

        // Build tenant default theme summary if tenant has one
        val tenantDefaultTheme = (row["tenant_default_theme_id"] as? String)?.let { themeId ->
            ThemeSummary(
                id = themeId,
                name = row["tenant_default_theme_name"] as String,
                description = row["tenant_default_theme_description"] as? String,
            )
        }

        // Resolve theme cascade: template theme takes precedence over tenant default
        val resolvedTheme = templateTheme ?: tenantDefaultTheme

        // Deserialize draft_template_model from JSONB (comes as String or PGobject from mapToMap)
        // Every variant must have a draft with a templateModel - if not, it's a data integrity issue
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
            variantTags = variantTags,
            templateModel = templateModel,
            dataExamples = dataExamples,
            dataModel = row["data_model"] as? ObjectNode,
            themes = themes,
            defaultTheme = resolvedTheme,
        )
    }
}

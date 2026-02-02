package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.DataExamples
import app.epistola.template.model.TemplateModel
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
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
    val templateModel: TemplateModel?,
    val dataExamples: List<DataExample>,
    val dataModel: ObjectNode?,
    val themes: List<ThemeSummary>,
    val defaultTheme: ThemeSummary?,
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
                theme.description as default_theme_description
            FROM document_templates dt
            JOIN template_variants tv ON tv.template_id = dt.id
            LEFT JOIN template_versions ver ON ver.variant_id = tv.id AND ver.status = 'draft'
            LEFT JOIN themes theme ON theme.id = dt.theme_id
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
                    id = rs.getObject("id", java.util.UUID::class.java).toString(),
                    name = rs.getString("name"),
                    description = rs.getString("description"),
                )
            }
            .list()

        // Parse the data examples - DataExamples implements List<DataExample>
        val dataExamplesJson = row["data_examples"]
        val dataExamples: List<DataExample> = when (dataExamplesJson) {
            is DataExamples -> dataExamplesJson.toList()
            is List<*> -> {
                @Suppress("UNCHECKED_CAST")
                (dataExamplesJson as? List<DataExample>) ?: emptyList()
            }
            else -> emptyList()
        }

        // Parse variant tags
        @Suppress("UNCHECKED_CAST")
        val variantTags: Map<String, String> = (row["variant_tags"] as? Map<String, String>) ?: emptyMap()

        // Build default theme summary if template has one
        val defaultTheme = (row["default_theme_id"] as? java.util.UUID)?.let { themeId ->
            ThemeSummary(
                id = themeId.toString(),
                name = row["default_theme_name"] as String,
                description = row["default_theme_description"] as? String,
            )
        }

        EditorContext(
            templateName = row["template_name"] as String,
            variantTags = variantTags,
            templateModel = row["draft_template_model"] as? TemplateModel,
            dataExamples = dataExamples,
            dataModel = row["data_model"] as? ObjectNode,
            themes = themes,
            defaultTheme = defaultTheme,
        )
    }
}

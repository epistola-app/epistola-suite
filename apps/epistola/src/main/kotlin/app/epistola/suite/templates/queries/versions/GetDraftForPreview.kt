package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.template.model.TemplateModel
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Context for PDF preview, including the template's default theme and optional draft.
 */
data class PreviewContext(
    /** The draft's template model, null if no draft exists */
    val draftTemplateModel: TemplateModel?,
    /** The parent template's default theme ID for theme cascade */
    val templateThemeId: ThemeId?,
)

/**
 * Gets the preview context: draft template model (if exists) and template's default theme.
 * Returns null only if the template or variant doesn't exist.
 * Used for PDF preview generation.
 */
data class GetPreviewContext(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Query<PreviewContext?>

@Component
class GetPreviewContextHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetPreviewContext, PreviewContext?> {
    override fun handle(query: GetPreviewContext): PreviewContext? = jdbi.withHandle<PreviewContext?, Exception> { handle ->
        // LEFT JOIN on draft so we still get themeId even when there's no draft
        handle.createQuery(
            """
            SELECT
                ver.template_model as draft_template_model,
                dt.theme_id as template_theme_id
            FROM document_templates dt
            JOIN template_variants tv ON tv.template_id = dt.id
            LEFT JOIN template_versions ver ON ver.variant_id = tv.id AND ver.status = 'draft'
            WHERE tv.id = :variantId
              AND tv.template_id = :templateId
              AND dt.tenant_id = :tenantId
            """,
        )
            .bind("variantId", query.variantId)
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                val templateModel = rs.getObject("draft_template_model", TemplateModel::class.java)
                val themeId = rs.getObject("template_theme_id", UUID::class.java)?.let { ThemeId.of(it) }
                PreviewContext(
                    draftTemplateModel = templateModel,
                    templateThemeId = themeId,
                )
            }
            .findOne()
            .orElse(null)
    }
}

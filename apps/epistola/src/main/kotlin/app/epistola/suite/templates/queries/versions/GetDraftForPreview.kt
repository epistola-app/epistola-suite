package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.template.model.TemplateModel
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Context for PDF preview, including the template's default theme, tenant's default theme, and optional draft.
 */
data class PreviewContext(
    /** The draft's template model, null if no draft exists */
    val draftTemplateModel: TemplateModel?,
    /** The parent template's default theme ID for theme cascade */
    val templateThemeId: ThemeId?,
    /** The tenant's default theme ID for ultimate fallback in theme cascade */
    val tenantDefaultThemeId: ThemeId?,
)

/**
 * Internal row representation for JDBI mapping.
 * JDBI's Jackson plugin handles JSONB -> TemplateModel conversion via @Json annotation.
 */
private data class PreviewContextRow(
    @Json val draftTemplateModel: TemplateModel?,
    val templateThemeId: UUID?,
    val tenantDefaultThemeId: UUID?,
)

/**
 * Gets the preview context: draft template model (if exists), template's default theme, and tenant's default theme.
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
                dt.theme_id as template_theme_id,
                t.default_theme_id as tenant_default_theme_id
            FROM document_templates dt
            JOIN tenants t ON t.id = dt.tenant_id
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
            .mapTo<PreviewContextRow>()
            .findOne()
            .map { row ->
                PreviewContext(
                    draftTemplateModel = row.draftTemplateModel,
                    templateThemeId = row.templateThemeId?.let { ThemeId.of(it) },
                    tenantDefaultThemeId = row.tenantDefaultThemeId?.let { ThemeId.of(it) },
                )
            }
            .orElse(null)
    }
}

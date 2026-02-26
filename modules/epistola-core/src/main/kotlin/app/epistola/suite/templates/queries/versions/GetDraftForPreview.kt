package app.epistola.suite.templates.queries.versions

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.jdbi.v3.json.Json
import org.springframework.stereotype.Component

/**
 * Context for PDF preview, including the template's default theme, tenant's default theme, and optional draft.
 */
data class PreviewContext(
    /** The draft's template model, null if no draft exists */
    val draftTemplateModel: TemplateDocument?,
    /** The parent template's default theme ID for theme cascade */
    val templateThemeId: ThemeKey?,
    /** The tenant's default theme ID for ultimate fallback in theme cascade */
    val tenantDefaultThemeId: ThemeKey?,
)

/**
 * Internal row representation for JDBI mapping.
 * JDBI's Jackson plugin handles JSONB -> TemplateDocument conversion via @Json annotation.
 */
private data class PreviewContextRow(
    @Json val draftTemplateModel: TemplateDocument?,
    val templateThemeId: String?,
    val tenantDefaultThemeId: String?,
)

/**
 * Gets the preview context: draft template model (if exists), template's default theme, and tenant's default theme.
 * Returns null only if the template or variant doesn't exist.
 * Used for PDF preview generation.
 */
data class GetPreviewContext(
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
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
                dt.theme_key as template_theme_key,
                t.default_theme_key as tenant_default_theme_key
            FROM template_variants tv
            JOIN document_templates dt ON dt.tenant_key = tv.tenant_key AND dt.id = tv.template_key
            JOIN tenants t ON t.id = tv.tenant_key
            LEFT JOIN template_versions ver ON ver.tenant_key = tv.tenant_key AND ver.variant_key = tv.id AND ver.status = 'draft'
            WHERE tv.id = :variantId
              AND tv.template_key = :templateId
              AND tv.tenant_key = :tenantId
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
                    templateThemeId = row.templateThemeId?.let { ThemeKey.of(it) },
                    tenantDefaultThemeId = row.tenantDefaultThemeId?.let { ThemeKey.of(it) },
                )
            }
            .orElse(null)
    }
}

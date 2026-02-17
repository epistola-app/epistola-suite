package app.epistola.suite.templates.queries.variants

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.model.VariantSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetVariant(
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
) : Query<TemplateVariant?>

@Component
class GetVariantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetVariant, TemplateVariant?> {
    override fun handle(query: GetVariant): TemplateVariant? = jdbi.withHandle<TemplateVariant?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT tv.id, tv.tenant_id, tv.template_id, tv.title, tv.description, tv.attributes, tv.is_default, tv.created_at, tv.last_modified
                FROM template_variants tv
                WHERE tv.id = :variantId
                  AND tv.template_id = :templateId
                  AND tv.tenant_id = :tenantId
                """,
        )
            .bind("variantId", query.variantId)
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<TemplateVariant>()
            .findOne()
            .orElse(null)
    }
}

data class GetVariantSummaries(
    val tenantId: TenantId,
    val templateId: TemplateId,
) : Query<List<VariantSummary>>

@Component
class GetVariantSummariesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetVariantSummaries, List<VariantSummary>> {
    override fun handle(query: GetVariantSummaries): List<VariantSummary> = jdbi.withHandle<List<VariantSummary>, Exception> { handle ->
        handle.createQuery(
            """
                SELECT
                    tv.id,
                    tv.title,
                    tv.attributes,
                    tv.is_default,
                    EXISTS(SELECT 1 FROM template_versions ver WHERE ver.tenant_id = tv.tenant_id AND ver.variant_id = tv.id AND ver.status = 'draft') as has_draft,
                    COALESCE(
                        (SELECT jsonb_agg(ver.id ORDER BY ver.id)
                         FROM template_versions ver
                         WHERE ver.tenant_id = tv.tenant_id AND ver.variant_id = tv.id AND ver.status = 'published'),
                        '[]'::jsonb
                    ) as published_versions
                FROM template_variants tv
                WHERE tv.template_id = :templateId
                  AND tv.tenant_id = :tenantId
                ORDER BY tv.is_default DESC, tv.created_at ASC
                """,
        )
            .bind("templateId", query.templateId)
            .bind("tenantId", query.tenantId)
            .mapTo<VariantSummary>()
            .list()
    }
}

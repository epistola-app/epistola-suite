package app.epistola.suite.variants.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.variants.TemplateVariant
import app.epistola.suite.variants.VariantSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetVariant(
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
) : Query<TemplateVariant?>

@Component
class GetVariantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetVariant, TemplateVariant?> {
    override fun handle(query: GetVariant): TemplateVariant? = jdbi.withHandle<TemplateVariant?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT tv.id, tv.template_id, tv.tags, tv.created_at, tv.last_modified
                FROM template_variants tv
                JOIN document_templates dt ON tv.template_id = dt.id
                WHERE tv.id = :variantId
                  AND tv.template_id = :templateId
                  AND dt.tenant_id = :tenantId
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
    val templateId: Long,
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
                    tv.tags,
                    EXISTS(SELECT 1 FROM template_versions ver WHERE ver.variant_id = tv.id AND ver.status = 'draft') as has_draft,
                    (SELECT COUNT(*) FROM template_versions ver WHERE ver.variant_id = tv.id AND ver.status = 'published')::int as published_version_count,
                    (SELECT MAX(ver.version_number) FROM template_versions ver WHERE ver.variant_id = tv.id AND ver.status = 'published') as latest_published_version
                FROM template_variants tv
                WHERE tv.template_id = :templateId
                ORDER BY tv.created_at ASC
                """,
        )
            .bind("templateId", query.templateId)
            .mapTo<VariantSummary>()
            .list()
    }
}

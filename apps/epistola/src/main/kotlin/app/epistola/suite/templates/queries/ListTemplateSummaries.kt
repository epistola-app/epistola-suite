package app.epistola.suite.templates.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Summary of a template with variant statistics for list views.
 */
data class TemplateSummary(
    val id: Long,
    val name: String,
    val lastModified: OffsetDateTime,
    val variantCount: Int,
    val hasDraft: Boolean,
    val publishedVersionCount: Int,
)

data class ListTemplateSummaries(
    val tenantId: Long,
    val searchTerm: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<TemplateSummary>>

@Component
class ListTemplateSummariesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListTemplateSummaries, List<TemplateSummary>> {
    override fun handle(query: ListTemplateSummaries): List<TemplateSummary> = jdbi.withHandle<List<TemplateSummary>, Exception> { handle ->
        val sql = buildString {
            append(
                """
                SELECT
                    dt.id,
                    dt.name,
                    dt.last_modified,
                    COALESCE((SELECT COUNT(*) FROM template_variants tv WHERE tv.template_id = dt.id), 0)::int as variant_count,
                    COALESCE((SELECT bool_or(ver.status = 'draft')
                              FROM template_versions ver
                              JOIN template_variants tv ON ver.variant_id = tv.id
                              WHERE tv.template_id = dt.id), false) as has_draft,
                    COALESCE((SELECT COUNT(*)
                              FROM template_versions ver
                              JOIN template_variants tv ON ver.variant_id = tv.id
                              WHERE tv.template_id = dt.id AND ver.status = 'published'), 0)::int as published_version_count
                FROM document_templates dt
                WHERE dt.tenant_id = :tenantId
                """.trimIndent(),
            )
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm")
            }
            append(" ORDER BY dt.last_modified DESC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId)
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo<TemplateSummary>()
            .list()
    }
}

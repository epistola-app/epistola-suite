package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.feedback.FeedbackCategory
import app.epistola.suite.feedback.FeedbackStatus
import app.epistola.suite.feedback.FeedbackSummary
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class ListFeedback(
    val tenantKey: TenantKey,
    val status: FeedbackStatus? = null,
    val category: FeedbackCategory? = null,
    val sourceUrl: String? = null,
) : Query<List<FeedbackSummary>>

@Component
class ListFeedbackHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListFeedback, List<FeedbackSummary>> {
    override fun handle(query: ListFeedback): List<FeedbackSummary> = jdbi.withHandleUnchecked { handle ->
        val conditions = mutableListOf("f.tenant_key = :tenantKey")
        if (query.status != null) {
            conditions.add("f.status = :status")
        } else if (query.sourceUrl != null) {
            conditions.add("f.status IN ('OPEN', 'IN_PROGRESS')")
        }
        if (query.category != null) conditions.add("f.category = :category")
        if (query.sourceUrl != null) {
            conditions.add(
                """(f.source_url LIKE '%' || :pathname
                    OR f.source_url LIKE '%' || :pathname || '?%'
                    OR f.source_url LIKE '%' || :pathname || '#%')""",
            )
        }

        val where = conditions.joinToString(" AND ")

        val q = handle.createQuery(
            """
            SELECT f.id, f.tenant_key, f.title, f.category, f.status, f.priority,
                   f.source_url,
                   u.display_name AS created_by_name,
                   COALESCE(c.comment_count, 0) AS comment_count,
                   f.created_at, f.updated_at
            FROM feedback f
            LEFT JOIN users u ON u.id = f.created_by
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS comment_count
                FROM feedback_comments fc
                WHERE fc.tenant_key = f.tenant_key AND fc.feedback_id = f.id
            ) c ON true
            WHERE $where
            ORDER BY f.created_at DESC
            """,
        )
            .bind("tenantKey", query.tenantKey)

        if (query.status != null) q.bind("status", query.status.name)
        if (query.category != null) q.bind("category", query.category.name)
        if (query.sourceUrl != null) q.bind("pathname", query.sourceUrl)

        q.mapTo(FeedbackSummary::class.java).list()
    }
}

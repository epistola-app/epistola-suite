package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.FeedbackAsset
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class ListFeedbackAssets(
    val feedbackId: FeedbackId,
) : Query<List<FeedbackAsset>>

@Component
class ListFeedbackAssetsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListFeedbackAssets, List<FeedbackAsset>> {
    override fun handle(query: ListFeedbackAssets): List<FeedbackAsset> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT tenant_key, feedback_id, id, content_type, filename, created_at
            FROM feedback_assets
            WHERE tenant_key = :tenantKey AND feedback_id = :feedbackId
            ORDER BY created_at ASC
            """,
        )
            .bind("tenantKey", query.feedbackId.tenantKey)
            .bind("feedbackId", query.feedbackId.key.value)
            .mapTo(FeedbackAsset::class.java)
            .list()
    }
}

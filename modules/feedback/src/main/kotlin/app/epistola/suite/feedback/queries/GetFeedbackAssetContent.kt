package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.FeedbackAssetId
import app.epistola.suite.feedback.FeedbackAssetContent
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetFeedbackAssetContent(
    val id: FeedbackAssetId,
) : Query<FeedbackAssetContent?>

@Component
class GetFeedbackAssetContentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFeedbackAssetContent, FeedbackAssetContent?> {
    override fun handle(query: GetFeedbackAssetContent): FeedbackAssetContent? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT content, content_type, filename
            FROM feedback_assets
            WHERE tenant_key = :tenantKey AND feedback_id = :feedbackId AND id = :id
            """,
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("feedbackId", query.id.feedbackKey.value)
            .bind("id", query.id.key.value)
            .mapTo(FeedbackAssetContent::class.java)
            .findOne()
            .orElse(null)
    }
}

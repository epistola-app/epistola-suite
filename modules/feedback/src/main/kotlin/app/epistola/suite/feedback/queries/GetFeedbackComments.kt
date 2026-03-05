package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetFeedbackComments(
    val id: FeedbackId,
) : Query<List<FeedbackComment>>

@Component
class GetFeedbackCommentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFeedbackComments, List<FeedbackComment>> {
    override fun handle(query: GetFeedbackComments): List<FeedbackComment> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT * FROM feedback_comments
            WHERE tenant_key = :tenantKey AND feedback_id = :feedbackId
            ORDER BY created_at ASC
            """,
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("feedbackId", query.id.key.value)
            .mapTo(FeedbackComment::class.java)
            .list()
    }
}

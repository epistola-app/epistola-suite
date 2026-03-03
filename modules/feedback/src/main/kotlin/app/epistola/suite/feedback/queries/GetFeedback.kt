package app.epistola.suite.feedback.queries

import app.epistola.suite.common.ids.FeedbackId
import app.epistola.suite.feedback.Feedback
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class GetFeedback(
    val id: FeedbackId,
) : Query<Feedback?>

@Component
class GetFeedbackHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetFeedback, Feedback?> {
    override fun handle(query: GetFeedback): Feedback? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT * FROM feedback
            WHERE tenant_key = :tenantKey AND id = :id
            """,
        )
            .bind("tenantKey", query.id.tenantKey)
            .bind("id", query.id.key.value)
            .mapTo(Feedback::class.java)
            .findOne()
            .orElse(null)
    }
}

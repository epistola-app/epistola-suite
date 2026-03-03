package app.epistola.suite.feedback.queries

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class ListPendingSyncFeedback(
    val limit: Int = 50,
) : Query<List<Feedback>>

@Component
class ListPendingSyncFeedbackHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListPendingSyncFeedback, List<Feedback>> {
    override fun handle(query: ListPendingSyncFeedback): List<Feedback> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT * FROM feedback
            WHERE sync_status = :syncStatus
            ORDER BY created_at ASC
            LIMIT :limit
            """,
        )
            .bind("syncStatus", SyncStatus.PENDING.name)
            .bind("limit", query.limit)
            .mapTo(Feedback::class.java)
            .list()
    }
}

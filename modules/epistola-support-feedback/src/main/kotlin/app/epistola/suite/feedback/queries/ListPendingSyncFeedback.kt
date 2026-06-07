package app.epistola.suite.feedback.queries

import app.epistola.suite.feedback.Feedback
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class ListPendingSyncFeedback(
    val limit: Int = 50,
    val maxAttempts: Int = MAX_SYNC_ATTEMPTS,
) : Query<List<Feedback>>,
    SystemInternal {
    companion object {
        const val MAX_SYNC_ATTEMPTS = 5
    }
}

@Component
class ListPendingSyncFeedbackHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListPendingSyncFeedback, List<Feedback>> {
    override fun handle(query: ListPendingSyncFeedback): List<Feedback> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT tenant_key, id, title, description, category, status, priority,
                   source_url, console_logs, metadata, created_by,
                   created_at, updated_at, external_ref, external_url, sync_status, sync_attempts
            FROM feedback
            WHERE sync_status = :syncStatus AND sync_attempts < :maxAttempts
            ORDER BY created_at ASC
            LIMIT :limit
            """,
        )
            .bind("syncStatus", SyncStatus.PENDING.name)
            .bind("maxAttempts", query.maxAttempts)
            .bind("limit", query.limit)
            .mapTo(Feedback::class.java)
            .list()
    }
}

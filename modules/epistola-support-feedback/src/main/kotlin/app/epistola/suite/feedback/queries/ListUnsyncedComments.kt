package app.epistola.suite.feedback.queries

import app.epistola.suite.feedback.CommentSource
import app.epistola.suite.feedback.FeedbackComment
import app.epistola.suite.feedback.SyncStatus
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.feedback.ConditionalOnSupportFeedbackModule
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Local comments that were authored in the suite ([CommentSource.LOCAL]) but never made it to
 * the hub (no `external_comment_id`), on feedback that is already synced. These are comments
 * whose immediate push in [app.epistola.suite.feedback.sync.OnFeedbackCommentAdded] failed;
 * the retry scheduler re-pushes them. Inbound ([CommentSource.EXTERNAL]) comments are excluded.
 */
data class ListUnsyncedComments(
    val limit: Int = 50,
) : Query<List<FeedbackComment>>,
    SystemInternal

@Component
@ConditionalOnSupportFeedbackModule
class ListUnsyncedCommentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListUnsyncedComments, List<FeedbackComment>> {
    override fun handle(query: ListUnsyncedComments): List<FeedbackComment> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT c.tenant_key, c.feedback_id, c.id, c.body, c.author_name, c.author_email,
                   c.source, c.external_comment_id, c.created_at
            FROM feedback_comments c
            JOIN feedback f ON f.tenant_key = c.tenant_key AND f.id = c.feedback_id
            WHERE c.source = :source
              AND c.external_comment_id IS NULL
              AND f.sync_status = :syncStatus
              AND f.external_ref IS NOT NULL
            ORDER BY c.created_at ASC
            LIMIT :limit
            """,
        )
            .bind("source", CommentSource.LOCAL.name)
            .bind("syncStatus", SyncStatus.SYNCED.name)
            .bind("limit", query.limit)
            .mapTo(FeedbackComment::class.java)
            .list()
    }
}

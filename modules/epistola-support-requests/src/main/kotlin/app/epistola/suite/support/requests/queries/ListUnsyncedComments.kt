// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.CommentSource
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST_REPLY
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.createdUnderConsent
import app.epistola.suite.support.requests.consent.exchangedUnderConsent
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/**
 * Local comments that were authored in the suite ([CommentSource.LOCAL]) but never made it to
 * the support service (no `external_comment_id`), on feedback that is already synced. These are comments
 * whose immediate push in [app.epistola.suite.support.requests.sync.OnSupportRequestCommentAdded] failed;
 * the retry scheduler re-pushes them once their back-off is over. Inbound ([CommentSource.EXTERNAL]) comments are excluded, and
 * so are comments written while sharing was off
 * (see `createdUnderConsent`), and feedback not exchanged (see `exchangedUnderConsent`). Oldest first.
 */
data class ListUnsyncedComments(
    val limit: Int = 50,
) : Query<List<SupportRequestComment>>,
    SystemInternal

@Component
class ListUnsyncedCommentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListUnsyncedComments, List<SupportRequestComment>> {
    override fun handle(query: ListUnsyncedComments): List<SupportRequestComment> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            $SELECT_SUPPORT_REQUEST_REPLY
            JOIN support_requests f ON f.tenant_key = c.tenant_key AND f.id = c.request_id
            JOIN support_request_deliveries d ON d.tenant_key = c.tenant_key AND d.request_id = c.request_id
                AND d.connection_id = $REQUESTS_CONNECTION_SQL
            WHERE c.kind = 'REPLY'
              AND c.from_connection IS NULL
              AND dm.remote_id IS NULL
              AND d.state = :syncStatus
              AND d.remote_ref IS NOT NULL
              AND (dm.next_attempt_at IS NULL OR dm.next_attempt_at <= :now)
              AND ${exchangedUnderConsent("f")}
              AND ${createdUnderConsent("c")}
            ORDER BY c.seq ASC
            LIMIT :limit
            """,
        )
            .bind("syncStatus", SyncStatus.SYNCED.name)
            .bind("now", EpistolaClock.offsetDateTime())
            .bind("limit", query.limit)
            .mapTo(SupportRequestComment::class.java)
            .list()
    }
}

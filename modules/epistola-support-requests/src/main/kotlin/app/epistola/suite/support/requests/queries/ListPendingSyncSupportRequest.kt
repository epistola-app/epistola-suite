// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.exchangedWith
import app.epistola.suite.support.requests.selectSupportRequest
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Feedback still waiting to be sent and due (its back-off over), oldest first, of tenants whose
 * consent is in force: the retry sweep never loads what it may not send. FAILED feedback is still
 * waiting: nothing is given up for good, it is only tried less often.
 */
data class ListPendingSyncSupportRequest(
    val limit: Int = 50,
    /** The connection to send to; Epistola support's by default. */
    val connectionId: UUID,
) : Query<List<SupportRequest>>,
    SystemInternal {
    companion object {
        /** After this many refusals in a row feedback reads as FAILED; it is still tried, at most every six hours. */
        const val MAX_SYNC_ATTEMPTS = 5
    }
}

@Component
class ListPendingSyncSupportRequestHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListPendingSyncSupportRequest, List<SupportRequest>> {
    override fun handle(query: ListPendingSyncSupportRequest): List<SupportRequest> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            ${selectSupportRequest(connectionSql(query.connectionId))}
            WHERE d.state IN (:pending, :failed)
              AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= :now)
              AND ${exchangedWith("f", "d")}
            ORDER BY f.created_at ASC
            LIMIT :limit
            """,
        )
            .bind("pending", SyncStatus.PENDING.name)
            .bind("failed", SyncStatus.FAILED.name)
            .bind("now", EpistolaClock.offsetDateTime())
            .bind("limit", query.limit)
            .mapTo(SupportRequest::class.java)
            .list()
    }
}

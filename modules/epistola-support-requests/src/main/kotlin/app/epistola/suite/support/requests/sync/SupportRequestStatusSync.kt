// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.exchangedWithConnection
import app.epistola.suite.support.requests.selectSupportRequest
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** Feedback whose status waits to be sent, and how often in a row the service refused it ([attempts]). */
data class UnsentSupportRequestStatus(val supportRequest: SupportRequest, val attempts: Int)

/**
 * Feedback at Epistola support whose status was changed here and not sent yet, and is due (its
 * back-off over), oldest change first; only feedback exchanged under the consent in force.
 */
data class ListUnsentSupportRequestStatuses(val limit: Int = 20, val connectionId: UUID = REQUESTS_CONNECTION) :
    Query<List<UnsentSupportRequestStatus>>,
    SystemInternal

@Component
class ListUnsentSupportRequestStatusesHandler(private val jdbi: Jdbi) : QueryHandler<ListUnsentSupportRequestStatuses, List<UnsentSupportRequestStatus>> {
    override fun handle(query: ListUnsentSupportRequestStatuses): List<UnsentSupportRequestStatus> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT r.*, d2.status_attempts AS status_sync_attempts
            FROM (${selectSupportRequest(connectionSql(query.connectionId))}
                  WHERE d.status_unsent
                    AND d.state = :synced AND d.remote_ref IS NOT NULL
                    AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= :now)
                    AND ${exchangedWithConnection("f", query.connectionId)}) r
            JOIN support_request_deliveries d2 ON d2.tenant_key = r.tenant_key AND d2.request_id = r.id
                AND d2.connection_id = ${connectionSql(query.connectionId)}
            ORDER BY r.updated_at ASC
            LIMIT :limit
            """,
        )
            .bind("synced", SyncStatus.SYNCED.name)
            .bind("now", EpistolaClock.offsetDateTime())
            .bind("limit", query.limit)
            .map { rs, ctx ->
                val supportRequest = ctx.findRowMapperFor(SupportRequest::class.java).orElseThrow().map(rs, ctx)
                UnsentSupportRequestStatus(supportRequest, rs.getInt("status_sync_attempts"))
            }
            .list()
    }
}

/**
 * The outcome of sending feedback's status: [sent] is the status the service took (it stays unsent
 * when it was changed again meanwhile); otherwise it is not tried again before [nextAttemptAt], and
 * [countAttempt] counts a refusal, so the next back-off doubles.
 */
data class RecordSupportRequestStatusSync(
    val id: SupportRequestId,
    val sent: SupportRequestStatus?,
    val nextAttemptAt: OffsetDateTime? = null,
    val countAttempt: Boolean = false,
    /** The connection it was sent to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Command<Boolean>,
    SystemInternal

@Component
class RecordSupportRequestStatusSyncHandler(private val jdbi: Jdbi) : CommandHandler<RecordSupportRequestStatusSync, Boolean> {
    override fun handle(command: RecordSupportRequestStatusSync): Boolean = jdbi.withHandleUnchecked { handle ->
        val update = if (command.sent != null) {
            handle.createUpdate(
                """
                UPDATE support_request_deliveries d SET status_unsent = FALSE, next_attempt_at = NULL, status_attempts = 0
                FROM support_requests f
                WHERE f.tenant_key = d.tenant_key AND f.id = d.request_id
                  AND d.tenant_key = :tenantKey AND d.request_id = :id AND d.connection_id = ${connectionSql(command.connectionId)}
                  AND f.status = :status
                """,
            ).bind("status", command.sent.name)
        } else {
            handle.createUpdate(
                """
                UPDATE support_request_deliveries SET next_attempt_at = :next,
                    status_attempts = status_attempts + CASE WHEN :count THEN 1 ELSE 0 END
                WHERE tenant_key = :tenantKey AND request_id = :id AND connection_id = ${connectionSql(command.connectionId)} AND status_unsent
                """,
            ).bind("next", command.nextAttemptAt).bind("count", command.countAttempt)
        }
        update.bind("tenantKey", command.id.tenantKey).bind("id", command.id.key.value).execute() > 0
    }
}

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.sync

import app.epistola.suite.common.ids.SupportRequestAssetId
import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.createdUnderConsent
import app.epistola.suite.support.requests.consent.exchangedUnderConsent
import app.epistola.suite.support.requests.consent.exchangedWith
import app.epistola.suite.support.requests.consent.mayGoTo
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/** One item the retry sweep sends to Epistola support. */
sealed interface OutboundSupportRequestItem {
    /** The feedback itself, still `PENDING` or `FAILED`. */
    data class Submission(val id: SupportRequestId) : OutboundSupportRequestItem

    /** A screenshot or other asset not sent yet. */
    data class Asset(val id: SupportRequestAssetId) : OutboundSupportRequestItem

    /** A local comment not sent yet. */
    data class Comment(val id: SupportRequestCommentId) : OutboundSupportRequestItem

    /** A status changed here, still [status] and not sent yet. */
    data class Status(val id: SupportRequestId, val status: SupportRequestStatus) : OutboundSupportRequestItem
}

/**
 * Whether [item] may still be sent to connection [connectionId], read right before its call: the sweep
 * lists a batch, and a withdrawal that lands while it works through it stops what it has not started
 * yet. The same predicates as the sweep's lists (`exchangedWith`, `mayGoTo`), for one row.
 */
data class IsSupportRequestItemStillToSend(
    val item: OutboundSupportRequestItem,
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Query<Boolean>,
    SystemInternal

@Component
class IsSupportRequestItemStillToSendHandler(private val jdbi: Jdbi) : QueryHandler<IsSupportRequestItemStillToSend, Boolean> {
    override fun handle(query: IsSupportRequestItemStillToSend): Boolean = jdbi.withHandleUnchecked { handle ->
        val connection = connectionSql(query.connectionId)
        when (val item = query.item) {
            is OutboundSupportRequestItem.Submission -> handle.createQuery(
                """
                SELECT EXISTS (SELECT 1 FROM support_requests f
                               JOIN support_request_deliveries d ON d.tenant_key = f.tenant_key AND d.request_id = f.id
                                   AND d.connection_id = $connection
                               WHERE f.tenant_key = :tenantKey AND f.id = :feedbackId
                                 AND d.state IN (:pending, :failed)
                                 AND ${exchangedWith("f", "d")})
                """,
            )
                .bind("tenantKey", item.id.tenantKey)
                .bind("feedbackId", item.id.key.value)
                .bind("pending", SyncStatus.PENDING.name)
                .bind("failed", SyncStatus.FAILED.name)

            is OutboundSupportRequestItem.Asset -> handle.createQuery(
                """
                SELECT EXISTS (SELECT 1 FROM support_request_assets a
                               JOIN support_requests f ON f.tenant_key = a.tenant_key AND f.id = a.request_id
                               JOIN support_request_deliveries d ON d.tenant_key = a.tenant_key AND d.request_id = a.request_id
                                   AND d.connection_id = $connection
                               LEFT JOIN support_request_delivered_items da ON da.tenant_key = a.tenant_key AND da.request_id = a.request_id
                                   AND da.item_id = a.id AND da.connection_id = d.connection_id
                               WHERE a.tenant_key = :tenantKey AND a.request_id = :feedbackId AND a.id = :id
                                 AND da.sent_at IS NULL
                                 AND ${mayGoTo("a", "da", "d")}
                                 AND ${exchangedWith("f", "d")})
                """,
            )
                .bind("tenantKey", item.id.tenantKey)
                .bind("feedbackId", item.id.supportRequestKey.value)
                .bind("id", item.id.key.value)

            is OutboundSupportRequestItem.Comment -> handle.createQuery(
                """
                SELECT EXISTS (SELECT 1 FROM support_request_messages c
                               JOIN support_requests f ON f.tenant_key = c.tenant_key AND f.id = c.request_id
                               JOIN support_request_deliveries d ON d.tenant_key = c.tenant_key AND d.request_id = c.request_id
                                   AND d.connection_id = $connection
                               LEFT JOIN support_request_delivered_items dm ON dm.tenant_key = c.tenant_key AND dm.request_id = c.request_id
                                   AND dm.item_id = c.id AND dm.connection_id = d.connection_id
                               WHERE c.tenant_key = :tenantKey AND c.request_id = :feedbackId AND c.id = :id
                                 AND c.kind = 'REPLY' AND c.from_connection IS DISTINCT FROM d.connection_id
                                 AND dm.remote_id IS NULL
                                 AND ${mayGoTo("c", "dm", "d")}
                                 AND ${exchangedWith("f", "d")})
                """,
            )
                .bind("tenantKey", item.id.tenantKey)
                .bind("feedbackId", item.id.supportRequestKey.value)
                .bind("id", item.id.key.value)

            is OutboundSupportRequestItem.Status -> handle.createQuery(
                """
                SELECT EXISTS (SELECT 1 FROM support_requests f
                               JOIN support_request_deliveries d ON d.tenant_key = f.tenant_key AND d.request_id = f.id
                                   AND d.connection_id = $connection
                               WHERE f.tenant_key = :tenantKey AND f.id = :feedbackId
                                 AND d.status_unsent AND f.status = :status
                                 AND ${exchangedWith("f", "d")})
                """,
            )
                .bind("tenantKey", item.id.tenantKey)
                .bind("feedbackId", item.id.key.value)
                .bind("status", item.status.name)
        }
            .mapTo(Boolean::class.java)
            .one()
    }
}

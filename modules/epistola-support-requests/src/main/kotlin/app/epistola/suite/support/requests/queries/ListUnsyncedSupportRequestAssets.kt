// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.SupportRequestAssetId
import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.createdUnderConsent
import app.epistola.suite.support.requests.consent.exchangedUnderConsent
import app.epistola.suite.support.requests.consent.exchangedWith
import app.epistola.suite.support.requests.consent.mayGoTo
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/** An asset the retry sweep still has to send. */
data class UnsyncedSupportRequestAsset(
    val tenantKey: TenantKey,
    val requestId: UUID,
    val id: UUID,
    /** Refusals so far: how far it backs off. */
    val syncAttempts: Int = 0,
) {
    val assetId: SupportRequestAssetId get() = SupportRequestAssetId(SupportRequestAssetKey.of(id), SupportRequestId(SupportRequestKey(requestId), TenantId(tenantKey)))
}

/**
 * Assets not sent yet, and due (their back-off over), of feedback already sent to the support service,
 * oldest first, of feedback that is exchanged (see `exchangedUnderConsent`), added while sharing was
 * on (see `createdUnderConsent`). An asset goes after the feedback it belongs to,
 * because the support service addresses it by that feedback's id.
 */
data class ListUnsyncedSupportRequestAssets(
    val limit: Int = 20,
    /** The connection to send to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Query<List<UnsyncedSupportRequestAsset>>,
    SystemInternal

@Component
class ListUnsyncedSupportRequestAssetsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListUnsyncedSupportRequestAssets, List<UnsyncedSupportRequestAsset>> {
    override fun handle(query: ListUnsyncedSupportRequestAssets): List<UnsyncedSupportRequestAsset> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            """
            SELECT a.tenant_key, a.request_id, a.id, COALESCE(da.attempts, 0) AS sync_attempts
            FROM support_request_assets a
            JOIN support_requests f ON f.tenant_key = a.tenant_key AND f.id = a.request_id
            JOIN support_request_deliveries d ON d.tenant_key = a.tenant_key AND d.request_id = a.request_id
                AND d.connection_id = ${connectionSql(query.connectionId)}
            LEFT JOIN support_request_delivered_items da ON da.tenant_key = a.tenant_key AND da.request_id = a.request_id
                AND da.item_id = a.id AND da.connection_id = d.connection_id
            WHERE da.sent_at IS NULL
              AND (da.next_attempt_at IS NULL OR da.next_attempt_at <= :now)
              AND d.state = :synced
              AND d.remote_ref IS NOT NULL
              AND ${exchangedWith("f", "d")}
              AND ${mayGoTo("a", "da", "d")}
            ORDER BY a.created_at ASC
            LIMIT :limit
            """,
        )
            .bind("now", EpistolaClock.offsetDateTime())
            .bind("synced", SyncStatus.SYNCED.name)
            .bind("limit", query.limit)
            .mapTo(UnsyncedSupportRequestAsset::class.java)
            .list()
    }
}

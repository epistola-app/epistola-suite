// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestAssetId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.exchangedWithLocked
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/** Marks an asset as sent to the support service, so it is not sent again. */
data class MarkSupportRequestAssetSynced(
    val id: SupportRequestAssetId,
    /** The connection it was sent to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Command<Boolean>,
    SystemInternal

@Component
class MarkSupportRequestAssetSyncedHandler(
    private val jdbi: Jdbi,
) : CommandHandler<MarkSupportRequestAssetSynced, Boolean> {
    override fun handle(command: MarkSupportRequestAssetSynced): Boolean = jdbi.withHandleUnchecked { handle ->
        handle.createUpdate(
            """
            INSERT INTO support_request_delivered_items (tenant_key, request_id, item_id, connection_id, sent_at)
            SELECT a.tenant_key, a.request_id, a.id, ${connectionSql(command.connectionId)}, :now
            FROM support_request_assets a
            WHERE a.tenant_key = :tenantKey AND a.request_id = :feedbackId AND a.id = :id
            ON CONFLICT (tenant_key, request_id, item_id, connection_id) DO UPDATE SET sent_at = EXCLUDED.sent_at
                WHERE support_request_delivered_items.sent_at IS NULL
            """,
        )
            .bind("now", EpistolaClock.offsetDateTime())
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.supportRequestKey.value)
            .bind("id", command.id.key.value)
            .execute() > 0
    }
}

/**
 * An asset could not be sent: not before [nextAttemptAt], and with [countAttempt] one attempt more
 * (a refusal; a server error on this asset alone is not counted).
 */
data class RecordSupportRequestAssetSyncFailure(
    val id: SupportRequestAssetId,
    val nextAttemptAt: java.time.OffsetDateTime? = null,
    val countAttempt: Boolean = true,
    /** The connection it was sent to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Command<Boolean>,
    SystemInternal

@Component
class RecordSupportRequestAssetSyncFailureHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RecordSupportRequestAssetSyncFailure, Boolean> {
    override fun handle(command: RecordSupportRequestAssetSyncFailure): Boolean = jdbi.withHandleUnchecked { handle ->
        handle.createUpdate(
            """
            INSERT INTO support_request_delivered_items (tenant_key, request_id, item_id, connection_id, attempts, next_attempt_at)
            SELECT a.tenant_key, a.request_id, a.id, ${connectionSql(command.connectionId)}, :count, :nextAttemptAt
            FROM support_request_assets a
            WHERE a.tenant_key = :tenantKey AND a.request_id = :feedbackId AND a.id = :id
            ON CONFLICT (tenant_key, request_id, item_id, connection_id) DO UPDATE
                SET attempts = support_request_delivered_items.attempts + :count, next_attempt_at = EXCLUDED.next_attempt_at
                WHERE support_request_delivered_items.sent_at IS NULL
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.supportRequestKey.value)
            .bind("id", command.id.key.value)
            .bind("count", if (command.countAttempt) 1 else 0)
            .bind("nextAttemptAt", command.nextAttemptAt)
            .execute() > 0
    }
}

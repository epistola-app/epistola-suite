// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.exchangedWithLocked
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

data class UpdateSupportRequestCommentExternalRef(
    val id: SupportRequestCommentId,
    val externalCommentId: String,
    /** The connection it was sent to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Command<Boolean>,
    SystemInternal

@Component
class UpdateSupportRequestCommentExternalRefHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateSupportRequestCommentExternalRef, Boolean> {
    override fun handle(command: UpdateSupportRequestCommentExternalRef): Boolean = jdbi.withHandleUnchecked { handle ->
        val rows = handle.createUpdate(
            """
            INSERT INTO support_request_delivered_items (tenant_key, request_id, item_id, connection_id, remote_id)
            SELECT c.tenant_key, c.request_id, c.id, ${connectionSql(command.connectionId)}, :externalCommentId
            FROM support_request_messages c
            WHERE c.tenant_key = :tenantKey AND c.request_id = :feedbackId AND c.id = :id
            ON CONFLICT (tenant_key, request_id, item_id, connection_id) DO UPDATE SET remote_id = EXCLUDED.remote_id
            """,
        )
            .bind("externalCommentId", command.externalCommentId)
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.supportRequestKey.value)
            .bind("id", command.id.key.value)
            .execute()
        rows > 0
    }
}

/**
 * A local comment could not be sent: it waits until [nextAttemptAt]
 * ([app.epistola.suite.support.requests.sync.SupportRequestSyncBackoff]), so a comment the support service keeps
 * refusing is tried less and less often, never holds back newer ones, and is never given up for good.
 */
data class RecordSupportRequestCommentSyncFailure(
    val id: SupportRequestCommentId,
    /** Not tried again before this. */
    val nextAttemptAt: java.time.OffsetDateTime? = null,
    /** One attempt more: a refusal. A server error on this comment alone is not counted. */
    val countAttempt: Boolean = true,
    /** The connection it was sent to; Epistola support's by default. */
    val connectionId: UUID = REQUESTS_CONNECTION,
) : Command<Boolean>,
    SystemInternal

@Component
class RecordSupportRequestCommentSyncFailureHandler(
    private val jdbi: Jdbi,
) : CommandHandler<RecordSupportRequestCommentSyncFailure, Boolean> {
    override fun handle(command: RecordSupportRequestCommentSyncFailure): Boolean = jdbi.withHandleUnchecked { handle ->
        handle.createUpdate(
            """
            INSERT INTO support_request_delivered_items (tenant_key, request_id, item_id, connection_id, attempts, next_attempt_at)
            SELECT c.tenant_key, c.request_id, c.id, ${connectionSql(command.connectionId)}, :count, :nextAttemptAt
            FROM support_request_messages c
            WHERE c.tenant_key = :tenantKey AND c.request_id = :feedbackId AND c.id = :id
            ON CONFLICT (tenant_key, request_id, item_id, connection_id) DO UPDATE
                SET attempts = support_request_delivered_items.attempts + :count, next_attempt_at = EXCLUDED.next_attempt_at
                WHERE support_request_delivered_items.remote_id IS NULL
            """,
        )
            .bind("count", if (command.countAttempt) 1 else 0)
            .bind("nextAttemptAt", command.nextAttemptAt)
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.supportRequestKey.value)
            .bind("id", command.id.key.value)
            .execute() > 0
    }
}

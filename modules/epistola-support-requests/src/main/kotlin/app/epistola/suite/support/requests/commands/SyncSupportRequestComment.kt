// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestCommentKey
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.CommentSource
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST_REPLY
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class SyncSupportRequestComment(
    val tenantKey: TenantKey,
    val supportRequestId: SupportRequestKey,
    val body: String,
    val authorName: String,
    val authorEmail: String?,
    val externalCommentId: String,
) : Command<SupportRequestComment?>,
    SystemInternal

@Component
class SyncSupportRequestCommentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<SyncSupportRequestComment, SupportRequestComment?> {
    override fun handle(command: SyncSupportRequestComment): SupportRequestComment? = jdbi.withHandleUnchecked { handle ->
        // A reply from the connection, under its id for it: one that arrives again is stored once.
        val commentKey = SupportRequestCommentKey.generate()
        val inserted = handle.createUpdate(
            """
            INSERT INTO support_request_messages (
                tenant_key, request_id, id, kind, body, author_name, author_email, from_connection, remote_id, created_at
            )
            VALUES (
                :tenantKey, :feedbackId, :id, 'REPLY', :body, :authorName, :authorEmail, $REQUESTS_CONNECTION_SQL, :externalCommentId, NOW()
            )
            ON CONFLICT (tenant_key, from_connection, remote_id) WHERE remote_id IS NOT NULL DO NOTHING
            """,
        )
            .bind("tenantKey", command.tenantKey)
            .bind("feedbackId", command.supportRequestId.value)
            .bind("id", commentKey.value)
            .bind("body", command.body)
            .bind("authorName", command.authorName)
            .bind("authorEmail", command.authorEmail)
            .bind("externalCommentId", command.externalCommentId)
            .execute()
        if (inserted == 0) return@withHandleUnchecked null
        handle.createQuery("$SELECT_SUPPORT_REQUEST_REPLY WHERE c.tenant_key = :tenantKey AND c.request_id = :feedbackId AND c.id = :id")
            .bind("tenantKey", command.tenantKey)
            .bind("feedbackId", command.supportRequestId.value)
            .bind("id", commentKey.value)
            .mapTo(SupportRequestComment::class.java)
            .one()
    }
}

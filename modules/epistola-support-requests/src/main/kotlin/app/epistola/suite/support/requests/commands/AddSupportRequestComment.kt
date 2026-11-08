// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestCommentId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.CommentSource
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST_REPLY
import app.epistola.suite.support.requests.SupportRequestComment
import app.epistola.suite.support.requests.consent.lockedConsentInForceId
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class AddSupportRequestComment(
    val id: SupportRequestCommentId,
    val body: String,
    val authorName: String,
    val authorEmail: String?,
    /** The user who wrote it, whose person reference goes in place of the name while tenants are confidential. */
    val authorId: UserKey? = null,
) : Command<SupportRequestComment>,
    RequiresPermission,
    // Its IMMEDIATE handler sends the comment to the support service: no open transaction.
    SelfManagedTransaction {
    override val permission get() = Permission.SUPPORT_REQUEST_SUBMIT
    override val tenantKey get() = id.tenantKey

    init {
        require(body.isNotBlank()) { "Comment body is required" }
    }
}

@Component
class AddSupportRequestCommentHandler(
    private val jdbi: Jdbi,
) : CommandHandler<AddSupportRequestComment, SupportRequestComment> {
    override fun handle(command: AddSupportRequestComment): SupportRequestComment = jdbi.withHandleUnchecked { handle ->
        handle.createUpdate(
            """
            INSERT INTO support_request_messages (
                tenant_key, request_id, id, kind, body, author_name, author_email, author_id, created_at, sync_consent_id
            )
            VALUES (
                :tenantKey, :feedbackId, :id, 'REPLY', :body, :authorName, :authorEmail,
                (SELECT id FROM users WHERE id = :authorId), NOW(), ${lockedConsentInForceId(":tenantKey")}
            )
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.supportRequestKey.value)
            .bind("id", command.id.key.value)
            .bind("body", command.body)
            .bind("authorName", command.authorName)
            .bind("authorEmail", command.authorEmail)
            .bind("authorId", command.authorId?.value)
            .execute()
        handle.createQuery("$SELECT_SUPPORT_REQUEST_REPLY WHERE c.tenant_key = :tenantKey AND c.request_id = :feedbackId AND c.id = :id")
            .bind("tenantKey", command.id.tenantKey)
            .bind("feedbackId", command.id.supportRequestKey.value)
            .bind("id", command.id.key.value)
            .mapTo(SupportRequestComment::class.java)
            .one()
    }
}

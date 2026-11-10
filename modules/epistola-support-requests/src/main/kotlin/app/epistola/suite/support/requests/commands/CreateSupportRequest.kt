// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.commands

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.SupportRequestLedger
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.lockedConsentInForce
import app.epistola.suite.support.requests.desk.SupportRequestHandlingPolicy
import app.epistola.suite.support.requests.sync.SupportRequestSyncPorts
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.inTransactionUnchecked
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class CreateSupportRequest(
    val id: SupportRequestId,
    val title: String,
    val description: String,
    val category: SupportRequestCategory,
    /** The priority the submitter asks for: stored as the requested priority and as the priority to start with. */
    val priority: SupportRequestPriority,
    val sourceUrl: String?,
    val consoleLogs: String?,
    val metadata: String?,
    val createdBy: UserKey,
    /** The server's log lines of the submitter's recent requests (a JSON array), when attached. */
    val serverLogs: String? = null,
) : Command<SupportRequest>,
    RequiresPermission,
    // Its IMMEDIATE handler pushes the created feedback to the support service: no open transaction.
    SelfManagedTransaction {
    override val permission get() = Permission.SUPPORT_REQUEST_SUBMIT
    override val tenantKey get() = id.tenantKey

    init {
        require(title.isNotBlank()) { "Title is required" }
        require(description.isNotBlank()) { "Description is required" }
    }
}

@Component
class CreateSupportRequestHandler(
    private val jdbi: Jdbi,
    private val handling: SupportRequestHandlingPolicy,
    private val ports: SupportRequestSyncPorts,
) : CommandHandler<CreateSupportRequest, SupportRequest> {
    override fun handle(command: CreateSupportRequest): SupportRequest {
        val supportRequestKey = command.id.key
        val tenantKey = command.id.tenantKey

        // Who handles new requests: the saved choice, or the configuration; only while its port is wired.
        val target = handling.connectionForNewRequests()?.takeIf { ports.port(it)?.isEnabled() == true }

        return jdbi.inTransactionUnchecked { handle ->
            handle.createUpdate(
                """
                INSERT INTO support_requests (
                    tenant_key, id, title, category, status, priority, requested_priority,
                    source_url, console_logs, server_logs, metadata, created_by,
                    created_at, updated_at
                )
                VALUES (
                    :tenantKey, :id, :title, :category, :status, :priority, :priority,
                    :sourceUrl, :consoleLogs, :serverLogs, CAST(:metadata AS JSONB), :createdBy,
                    NOW(), NOW()
                )
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("id", supportRequestKey.value)
                .bind("title", command.title)
                .bind("category", command.category.name)
                .bind("status", SupportRequestStatus.OPEN.name)
                .bind("priority", command.priority.name)
                .bind("sourceUrl", command.sourceUrl)
                .bind("consoleLogs", command.consoleLogs)
                .bind("serverLogs", command.serverLogs)
                .bind("metadata", command.metadata)
                .bind("createdBy", command.createdBy.value)
                .execute()
            // The description is the request's question: a message with the request's id.
            handle.createUpdate(
                """
                INSERT INTO support_request_messages (tenant_key, request_id, id, kind, body)
                VALUES (:tenantKey, :id, :id, 'QUESTION', :description)
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("id", supportRequestKey.value)
                .bind("description", command.description)
                .execute()
            // It goes to the connection that handles new requests while the tenant shares support
            // requests with it, read locked in the statement that records it (FOR SHARE: a withdrawal
            // under way is waited for and then read as withdrawn); the connection then handles it and
            // the question goes along (its ledger). Otherwise the installation's inbox handles it.
            if (target != null) {
                val connection = connectionSql(target)
                val delivered = handle.createUpdate(
                    """
                    INSERT INTO support_request_deliveries (tenant_key, request_id, connection_id, state)
                    SELECT f.tenant_key, f.id, $connection, :pending
                    FROM support_requests f
                    WHERE f.tenant_key = :tenantKey AND f.id = :id AND ${lockedConsentInForce(":tenantKey", connection)}
                    """,
                )
                    .bind("tenantKey", tenantKey)
                    .bind("id", supportRequestKey.value)
                    .bind("pending", SyncStatus.PENDING.name)
                    .execute()
                if (delivered > 0) {
                    handle.createUpdate("UPDATE support_requests SET handler_connection = :connection WHERE tenant_key = :tenantKey AND id = :id")
                        .bind("connection", target)
                        .bind("tenantKey", tenantKey)
                        .bind("id", supportRequestKey.value)
                        .execute()
                    SupportRequestLedger.recordHistory(handle, tenantKey, supportRequestKey.value, target)
                }
            }
            handle.createQuery("$SELECT_SUPPORT_REQUEST WHERE f.tenant_key = :tenantKey AND f.id = :id")
                .bind("tenantKey", tenantKey)
                .bind("id", supportRequestKey.value)
                .mapTo(SupportRequest::class.java)
                .one()
        }
    }
}

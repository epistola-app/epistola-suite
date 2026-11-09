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
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION_SQL
import app.epistola.suite.support.requests.consent.SupportRequestLedger
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.lockedConsentInForce
import app.epistola.suite.support.requests.consent.lockedConsentInForceId
import app.epistola.suite.support.requests.desk.SupportRequestHandlingPolicy
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
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
    private val supportRequestSyncPort: SupportRequestSyncPort,
    private val handling: SupportRequestHandlingPolicy,
    private val ports: SupportRequestSyncPorts,
) : CommandHandler<CreateSupportRequest, SupportRequest> {
    override fun handle(command: CreateSupportRequest): SupportRequest {
        val supportRequestKey = command.id.key
        val tenantKey = command.id.tenantKey

        // PENDING (which the outbound sync picks up) only when a sync target is wired AND the
        // tenant consented to sending feedback to Epistola support; otherwise it stays local.
        // The consent is read in the same statement, locked (FOR SHARE): a withdrawal under way is
        // waited for and then read as withdrawn, so it cannot slip past.
        val portEnabled = supportRequestSyncPort.isEnabled()
        // Who handles new requests: the saved choice, or the configuration.
        val target = handling.connectionForNewRequests()
        val toConnection = target == REQUESTS_CONNECTION
        // Another connection than Epistola support's (ADR 0053 step 3): while its port is wired.
        val toOther = target?.takeIf { it != REQUESTS_CONNECTION && ports.port(it)?.isEnabled() == true }

        return jdbi.inTransactionUnchecked { handle ->
            handle.createUpdate(
                """
                INSERT INTO support_requests (
                    tenant_key, id, title, category, status, priority, requested_priority,
                    source_url, console_logs, server_logs, metadata, created_by,
                    created_at, updated_at, sync_consent_id
                )
                VALUES (
                    :tenantKey, :id, :title, :category, :status, :priority, :priority,
                    :sourceUrl, :consoleLogs, :serverLogs, CAST(:metadata AS JSONB), :createdBy,
                    NOW(), NOW(),
                    CASE WHEN :portEnabled THEN ${lockedConsentInForceId(":tenantKey")} END
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
                .bind("portEnabled", portEnabled)
                .execute()
            // The description is the request's question: a message with the request's id, under the same grant.
            handle.createUpdate(
                """
                INSERT INTO support_request_messages (tenant_key, request_id, id, kind, body, sync_consent_id)
                SELECT f.tenant_key, f.id, f.id, 'QUESTION', :description, f.sync_consent_id
                FROM support_requests f WHERE f.tenant_key = :tenantKey AND f.id = :id
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("id", supportRequestKey.value)
                .bind("description", command.description)
                .execute()
            // It goes to the support connection only when that connection handles new requests, a
            // sync target is wired and the tenant's consent is in force, read in the statement that
            // records it; the connection then handles it. Otherwise the installation's inbox does.
            val delivered = handle.createUpdate(
                """
                INSERT INTO support_request_deliveries (tenant_key, request_id, connection_id, state)
                SELECT f.tenant_key, f.id, $REQUESTS_CONNECTION_SQL, :pending
                FROM support_requests f
                WHERE f.tenant_key = :tenantKey AND f.id = :id AND f.sync_consent_id IS NOT NULL
                  AND :toConnection
                  AND ${lockedConsentInForce(":tenantKey")}
                """,
            )
                .bind("tenantKey", tenantKey)
                .bind("id", supportRequestKey.value)
                .bind("pending", SyncStatus.PENDING.name)
                .bind("toConnection", toConnection)
                .execute()
            if (delivered > 0) {
                handle.createUpdate("UPDATE support_requests SET handler_connection = $REQUESTS_CONNECTION_SQL WHERE tenant_key = :tenantKey AND id = :id")
                    .bind("tenantKey", tenantKey)
                    .bind("id", supportRequestKey.value)
                    .execute()
            }
            // To another connection while the tenant shares with it, read in the statement that records
            // it; it then handles the request, and the question goes with it (its ledger).
            if (toOther != null) {
                val connection = connectionSql(toOther)
                val deliveredOther = handle.createUpdate(
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
                if (deliveredOther > 0) {
                    handle.createUpdate("UPDATE support_requests SET handler_connection = :connection WHERE tenant_key = :tenantKey AND id = :id")
                        .bind("connection", toOther)
                        .bind("tenantKey", tenantKey)
                        .bind("id", supportRequestKey.value)
                        .execute()
                    SupportRequestLedger.recordHistory(handle, tenantKey, supportRequestKey.value, toOther)
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

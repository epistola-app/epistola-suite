// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.desk

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.lockedConsentInForce
import app.epistola.suite.support.requests.consent.lockedConsentInForceId
import app.epistola.suite.support.requests.sync.SupportRequestSyncKick
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.util.UUID

/** The request is handled by a support connection: only that connection sets its status and priority. */
class SupportRequestHandledElsewhereException(val tenant: TenantKey, val request: SupportRequestKey) : RuntimeException("Support request $request of tenant $tenant is handled by a support connection")

/** The request cannot be passed on; [reason] says why, in words for the desk. */
class SupportRequestNotEscalatedException(val reason: String) : RuntimeException(reason)

/** There is no such support request. */
class SupportRequestNotFoundException(val tenant: TenantKey, val request: SupportRequestKey) : RuntimeException("No support request $request in tenant $tenant")

/**
 * The desk writes on a request: a reply, which the requester sees and which goes to the connection
 * that handles the request when the tenant shares it, or an internal [note], which only the desk sees
 * and which never leaves the installation.
 */
data class AddSupportDeskMessage(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val body: String,
    val note: Boolean,
) : Command<UUID>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class AddSupportDeskMessageHandler(
    private val jdbi: Jdbi,
    private val kick: SupportRequestSyncKick,
) : CommandHandler<AddSupportDeskMessage, UUID> {
    override fun handle(command: AddSupportDeskMessage): UUID = jdbi.inTransaction<UUID, Exception> { handle ->
        requireRequest(handle, command.tenant, command.request)
        val principal = SecurityContext.current()
        val id = UUIDv7.generate()
        // A reply records the grant in force, as a requester's comment does, so it is sent only while
        // the tenant shares; a note records none, so nothing ever sends it.
        val grant = if (command.note) "NULL" else lockedConsentInForceId(":tenant")
        handle.createUpdate(
            """
            INSERT INTO support_request_messages (tenant_key, request_id, id, kind, body, author_name, author_email, author_id, created_at, sync_consent_id)
            VALUES (:tenant, :request, :id, :kind, :body, :authorName, :authorEmail, (SELECT id FROM users WHERE id = :authorId), NOW(), $grant)
            """,
        )
            .bind("tenant", command.tenant)
            .bind("request", command.request.value)
            .bind("id", id)
            .bind("kind", if (command.note) "NOTE" else "REPLY")
            .bind("body", command.body)
            .bind("authorName", principal.displayName)
            .bind("authorEmail", principal.email)
            .bind("authorId", principal.userId.value)
            .execute()
        if (!command.note) kick.kick()
        id
    }
}

/** The desk sets the status of a request the installation's inbox handles. */
data class SetSupportDeskStatus(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val status: SupportRequestStatus,
) : Command<Boolean>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class SetSupportDeskStatusHandler(private val jdbi: Jdbi) : CommandHandler<SetSupportDeskStatus, Boolean> {
    override fun handle(command: SetSupportDeskStatus): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        requireHandledHere(handle, command.tenant, command.request)
        handle.createUpdate(
            "UPDATE support_requests SET status = :status, updated_at = NOW() WHERE tenant_key = :tenant AND id = :request AND handler_connection IS NULL AND status <> :status",
        )
            .bind("tenant", command.tenant)
            .bind("request", command.request.value)
            .bind("status", command.status.name)
            .execute() > 0
    }
}

/** The desk sets the final priority of a request the installation's inbox handles. */
data class SetSupportDeskPriority(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val priority: SupportRequestPriority,
) : Command<Boolean>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class SetSupportDeskPriorityHandler(private val jdbi: Jdbi) : CommandHandler<SetSupportDeskPriority, Boolean> {
    override fun handle(command: SetSupportDeskPriority): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        requireHandledHere(handle, command.tenant, command.request)
        handle.createUpdate(
            "UPDATE support_requests SET priority = :priority, updated_at = NOW() WHERE tenant_key = :tenant AND id = :request AND handler_connection IS NULL AND priority <> :priority",
        )
            .bind("tenant", command.tenant)
            .bind("request", command.request.value)
            .bind("priority", command.priority.name)
            .execute() > 0
    }
}

/**
 * The desk passes a request the inbox handles on to [connectionId] (ADR 0053 §4): it is sent there as
 * any request is, shaped by that connection's privacy profile, the connection handles it from then on,
 * and its replies come back here. Refused, with the reason, when the tenant did not share support
 * requests when it was submitted or does not share them now: what was written while sharing was off
 * never leaves the installation.
 */
data class EscalateSupportRequest(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val connectionId: UUID,
) : Command<Unit>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class EscalateSupportRequestHandler(
    private val jdbi: Jdbi,
    private val port: SupportRequestSyncPort,
    private val kick: SupportRequestSyncKick,
) : CommandHandler<EscalateSupportRequest, Unit> {
    override fun handle(command: EscalateSupportRequest) {
        jdbi.inTransaction<Unit, Exception> { handle ->
            requireHandledHere(handle, command.tenant, command.request)
            val target = choices(handle, port).firstOrNull { it.connectionId == command.connectionId }
                ?: throw SupportRequestNotEscalatedException("That support connection does not take support requests now.")
            // The tenant's consent is read locked, as on every write that may be sent.
            val delivered = handle.createUpdate(
                """
                INSERT INTO support_request_deliveries (tenant_key, request_id, connection_id, state)
                SELECT f.tenant_key, f.id, :connection, :pending
                FROM support_requests f
                WHERE f.tenant_key = :tenant AND f.id = :request AND f.handler_connection IS NULL
                  AND f.sync_consent_id IS NOT NULL AND ${lockedConsentInForce(":tenant")}
                ON CONFLICT (tenant_key, request_id, connection_id) DO NOTHING
                """,
            )
                .bind("tenant", command.tenant)
                .bind("request", command.request.value)
                .bind("connection", target.connectionId)
                .bind("pending", SyncStatus.PENDING.name)
                .execute()
            if (delivered == 0) {
                throw SupportRequestNotEscalatedException(
                    "The tenant did not share support requests when this one was submitted, or does not share them now.",
                )
            }
            handle.createUpdate("UPDATE support_requests SET handler_connection = :connection, updated_at = NOW() WHERE tenant_key = :tenant AND id = :request")
                .bind("tenant", command.tenant)
                .bind("request", command.request.value)
                .bind("connection", target.connectionId)
                .execute()
        }
        kick.kick()
    }
}

private fun requireRequest(handle: Handle, tenant: TenantKey, request: SupportRequestKey): UUID? = handle.createQuery(
    "SELECT handler_connection FROM support_requests WHERE tenant_key = :tenant AND id = :request FOR UPDATE",
)
    .bind("tenant", tenant)
    .bind("request", request.value)
    .map { rs, _ -> Holder(rs.getObject("handler_connection", UUID::class.java)) }
    .findOne()
    .orElseThrow { SupportRequestNotFoundException(tenant, request) }
    .handler

private fun requireHandledHere(handle: Handle, tenant: TenantKey, request: SupportRequestKey) {
    if (requireRequest(handle, tenant, request) != null) throw SupportRequestHandledElsewhereException(tenant, request)
}

private class Holder(val handler: UUID?)

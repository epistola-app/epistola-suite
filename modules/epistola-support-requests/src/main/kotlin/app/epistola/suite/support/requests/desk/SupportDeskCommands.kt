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
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SyncStatus
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.consent.SupportRequestLedger
import app.epistola.suite.support.requests.consent.connectionSql
import app.epistola.suite.support.requests.consent.lockedConsentInForce
import app.epistola.suite.support.requests.consent.lockedConsentInForceId
import app.epistola.suite.support.requests.consent.markStatusUnsentSql
import app.epistola.suite.support.requests.sync.SupportRequestSyncKick
import app.epistola.suite.support.requests.sync.SupportRequestSyncPorts
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
        // A reply goes to every other connection the request is delivered to that the tenant shares with now; a note never.
        if (!command.note) SupportRequestLedger.recordItem(handle, command.tenant, command.request.value, id)
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
        val changed = handle.createUpdate(
            "UPDATE support_requests SET status = :status, updated_at = NOW() WHERE tenant_key = :tenant AND id = :request AND handler_connection IS NULL AND status <> :status",
        )
            .bind("tenant", command.tenant)
            .bind("request", command.request.value)
            .bind("status", command.status.name)
            .execute() > 0
        // The connections attached to the request are told (ADR 0053 step 3).
        if (changed) handle.createUpdate(markStatusUnsentSql()).bind("tenantKey", command.tenant).bind("id", command.request.value).execute()
        changed
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
    private val port: SupportRequestSyncPorts,
    private val kick: SupportRequestSyncKick,
) : CommandHandler<EscalateSupportRequest, Unit> {
    override fun handle(command: EscalateSupportRequest) {
        jdbi.inTransaction<Unit, Exception> { handle ->
            requireHandledHere(handle, command.tenant, command.request)
            val target = choices(handle, port).firstOrNull { it.connectionId == command.connectionId }
                ?: throw SupportRequestNotEscalatedException("That support connection does not take support requests now.")
            // The tenant's consent is read locked, as on every write that may be sent. Epistola support's
            // connection: shared when it was submitted and now; another connection: shared now, and
            // then what the request holds goes along (ADR 0053 step 3).
            val exchanged = if (target.connectionId == REQUESTS_CONNECTION) {
                "f.sync_consent_id IS NOT NULL AND ${lockedConsentInForce(":tenant")}"
            } else {
                lockedConsentInForce(":tenant", connectionSql(target.connectionId))
            }
            val delivered = handle.createUpdate(
                """
                INSERT INTO support_request_deliveries (tenant_key, request_id, connection_id, state)
                SELECT f.tenant_key, f.id, :connection, :pending
                FROM support_requests f
                WHERE f.tenant_key = :tenant AND f.id = :request AND f.handler_connection IS NULL
                  AND $exchanged
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
            SupportRequestLedger.recordHistory(handle, command.tenant, command.request.value, target.connectionId)
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

/**
 * The desk attaches [connectionId] to a request besides the connection or inbox that handles it (ADR
 * 0053 step 3): it gets the request and everything written on it but internal notes, shaped by its
 * privacy, and its replies come back here; the handler alone sets status and priority. Allowed as
 * passing a request on is: Epistola support's connection when the request was submitted while shared
 * and is shared now, another while the tenant shares with it now, and then what the request holds goes
 * along. Refused, with the reason, otherwise.
 */
data class AttachSupportConnection(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val connectionId: UUID,
) : Command<Unit>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class AttachSupportConnectionHandler(
    private val jdbi: Jdbi,
    private val port: SupportRequestSyncPorts,
    private val kick: SupportRequestSyncKick,
) : CommandHandler<AttachSupportConnection, Unit> {
    override fun handle(command: AttachSupportConnection) {
        jdbi.inTransaction<Unit, Exception> { handle -> attachConnection(handle, port, command.tenant, command.request, command.connectionId) }
        kick.kick()
    }
}

/**
 * Attaches [connectionId] to [request] besides its handler, as [AttachSupportConnection] does: allowed as
 * passing it on is, with what the request holds; [SupportRequestNotEscalatedException] with the reason
 * otherwise. For the desk and for an escalation a connection asked for.
 */
internal fun attachConnection(handle: Handle, ports: SupportRequestSyncPorts, tenant: TenantKey, request: SupportRequestKey, connectionId: UUID) {
    val handler = requireRequest(handle, tenant, request)
    if (handler == connectionId) throw SupportRequestNotEscalatedException("That support connection handles this request already.")
    val target = choices(handle, ports).firstOrNull { it.connectionId == connectionId }
        ?: throw SupportRequestNotEscalatedException("That support connection does not take support requests now.")
    val exchanged = if (target.connectionId == REQUESTS_CONNECTION) {
        "f.sync_consent_id IS NOT NULL AND ${lockedConsentInForce(":tenant")}"
    } else {
        lockedConsentInForce(":tenant", connectionSql(target.connectionId))
    }
    val attached = handle.createUpdate(
        """
        INSERT INTO support_request_deliveries (tenant_key, request_id, connection_id, state)
        SELECT f.tenant_key, f.id, :connection, :pending
        FROM support_requests f
        WHERE f.tenant_key = :tenant AND f.id = :request AND $exchanged
        ON CONFLICT (tenant_key, request_id, connection_id) DO NOTHING
        """,
    )
        .bind("tenant", tenant)
        .bind("request", request.value)
        .bind("connection", target.connectionId)
        .bind("pending", SyncStatus.PENDING.name)
        .execute()
    if (attached == 0) {
        throw SupportRequestNotEscalatedException("The tenant does not share support requests with ${target.name}, or it is on this request already.")
    }
    SupportRequestLedger.recordHistory(handle, tenant, request.value, target.connectionId)
}

/**
 * Connection [fromConnection] asked, through the protocol, to escalate [request] (ADR 0053 §4): the
 * connection it escalates to (`support_connections.escalates_to`) is attached, as the desk would. When
 * that cannot be done, an internal note says why, so the desk can act. Run by the poll, as the system.
 *
 * @return the connection attached; null when none was.
 */
data class EscalateFromConnection(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val fromConnection: UUID,
    val reason: String?,
) : Command<UUID?>,
    // The poll applies what a connection sent; no person asks for it.
    SystemInternal

@Component
class EscalateFromConnectionHandler(
    private val jdbi: Jdbi,
    private val port: SupportRequestSyncPorts,
    private val kick: SupportRequestSyncKick,
) : CommandHandler<EscalateFromConnection, UUID?> {
    override fun handle(command: EscalateFromConnection): UUID? {
        val attached = jdbi.inTransaction<UUID?, Exception> { handle ->
            val (fromName, target) = handle.createQuery(
                "SELECT c.name, c.escalates_to, t.name AS target_name FROM support_connections c LEFT JOIN support_connections t ON t.id = c.escalates_to WHERE c.id = :id",
            )
                .bind("id", command.fromConnection)
                .map { rs, _ -> rs.getString("name") to rs.getObject("escalates_to", UUID::class.java)?.let { it to rs.getString("target_name") } }
                .findOne()
                .orElse(null) ?: return@inTransaction null
            val asked = "$fromName asked to escalate this request" + (command.reason?.takeIf { it.isNotBlank() }?.let { ": ${it.trim()}" } ?: ".")
            if (target == null) {
                note(handle, command, fromName, "$asked No connection is set for $fromName to escalate to; pass it on or attach one here.")
                return@inTransaction null
            }
            try {
                attachConnection(handle, port, command.tenant, command.request, target.first)
                note(handle, command, fromName, "$asked It went to ${target.second} as well.")
                target.first
            } catch (e: SupportRequestNotEscalatedException) {
                note(handle, command, fromName, "$asked It could not go to ${target.second}: ${e.reason}")
                null
            }
        }
        if (attached != null) kick.kick()
        return attached
    }

    /** An internal note only the desk sees, written in the connection's name. */
    private fun note(handle: Handle, command: EscalateFromConnection, fromName: String, body: String) {
        handle.createUpdate(
            "INSERT INTO support_request_messages (tenant_key, request_id, id, kind, body, author_name, created_at) VALUES (:tenant, :request, :id, 'NOTE', :body, :author, NOW())",
        )
            .bind("tenant", command.tenant)
            .bind("request", command.request.value)
            .bind("id", UUIDv7.generate())
            .bind("body", body)
            .bind("author", fromName)
            .execute()
    }
}

/**
 * The desk detaches [connectionId] from a request it is attached to (not its handler): nothing more
 * goes there, what was still to go stays here, and its replies are no longer taken. What it has keeps.
 */
data class DetachSupportConnection(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val connectionId: UUID,
) : Command<Boolean>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class DetachSupportConnectionHandler(private val jdbi: Jdbi) : CommandHandler<DetachSupportConnection, Boolean> {
    override fun handle(command: DetachSupportConnection): Boolean = jdbi.inTransaction<Boolean, Exception> { handle ->
        val handler = requireRequest(handle, command.tenant, command.request)
        if (handler == command.connectionId) throw SupportRequestHandledElsewhereException(command.tenant, command.request)
        handle.createUpdate(
            "DELETE FROM support_request_delivered_items WHERE tenant_key = :tenant AND request_id = :request AND connection_id = :connection AND remote_id IS NULL AND sent_at IS NULL",
        )
            .bind("tenant", command.tenant).bind("request", command.request.value).bind("connection", command.connectionId).execute()
        handle.createUpdate("DELETE FROM support_request_deliveries WHERE tenant_key = :tenant AND request_id = :request AND connection_id = :connection")
            .bind("tenant", command.tenant).bind("request", command.request.value).bind("connection", command.connectionId).execute() > 0
    }
}

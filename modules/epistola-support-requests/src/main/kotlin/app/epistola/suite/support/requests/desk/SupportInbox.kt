// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.desk

import app.epistola.suite.common.PageRequest
import app.epistola.suite.common.Paging
import app.epistola.suite.common.ResultPage
import app.epistola.suite.common.ids.SupportRequestAssetKey
import app.epistola.suite.common.ids.SupportRequestKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.support.requests.SELECT_SUPPORT_REQUEST
import app.epistola.suite.support.requests.SupportRequest
import app.epistola.suite.support.requests.SupportRequestAssetContent
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestPriority
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.consent.consentInForce
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.jdbi.v3.core.mapper.RowMapper
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** Which requests the inbox shows by who handles them. */
enum class SupportInboxHandler {
    /** Every request. */
    ALL,

    /** Those the installation's own inbox handles. */
    HERE,

    /** Those handed to a support connection. */
    ELSEWHERE,
}

/** One row of the support desk's inbox. [handlerName] is the connection that handles it; null: the inbox. */
data class SupportInboxItem(
    val id: SupportRequestKey,
    val tenantKey: TenantKey,
    val tenantName: String,
    val title: String,
    val category: SupportRequestCategory,
    val status: SupportRequestStatus,
    val priority: SupportRequestPriority,
    val createdByName: String?,
    val replyCount: Int,
    val handlerName: String?,
    val createdAt: OffsetDateTime,
)

/**
 * The installation's support requests across tenants, newest first, paged in the database
 * (ADR 0053 §3). Filters combine; null is "any".
 */
data class ListSupportInbox(
    val status: SupportRequestStatus? = null,
    val tenant: TenantKey? = null,
    val priority: SupportRequestPriority? = null,
    val handler: SupportInboxHandler = SupportInboxHandler.ALL,
    val page: PageRequest = PageRequest(limit = PAGE_SIZE, offset = 0),
) : Query<ResultPage<SupportInboxItem>>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE

    companion object {
        const val PAGE_SIZE = 50
    }
}

@Component
class ListSupportInboxHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListSupportInbox, ResultPage<SupportInboxItem>> {
    override fun handle(query: ListSupportInbox): ResultPage<SupportInboxItem> = jdbi.withHandleUnchecked { handle ->
        val conditions = buildList {
            if (query.status != null) add("f.status = :status")
            if (query.tenant != null) add("f.tenant_key = :tenant")
            if (query.priority != null) add("f.priority = :priority")
            when (query.handler) {
                SupportInboxHandler.HERE -> add("f.handler_connection IS NULL")
                SupportInboxHandler.ELSEWHERE -> add("f.handler_connection IS NOT NULL")
                SupportInboxHandler.ALL -> Unit
            }
        }
        val where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        Paging.read(
            query.page,
            RowMapper { rs, _ ->
                SupportInboxItem(
                    id = SupportRequestKey.of(rs.getObject("id", UUID::class.java)),
                    tenantKey = TenantKey.of(rs.getString("tenant_key")),
                    tenantName = rs.getString("tenant_name"),
                    title = rs.getString("title"),
                    category = SupportRequestCategory.valueOf(rs.getString("category")),
                    status = SupportRequestStatus.valueOf(rs.getString("status")),
                    priority = SupportRequestPriority.valueOf(rs.getString("priority")),
                    createdByName = rs.getString("created_by_name"),
                    replyCount = rs.getInt("reply_count"),
                    handlerName = rs.getString("handler_name"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                )
            },
        ) {
            handle.createQuery(
                """
                SELECT f.id, f.tenant_key, t.name AS tenant_name, f.title, f.category, f.status, f.priority,
                       u.display_name AS created_by_name, sc.name AS handler_name, f.created_at,
                       (SELECT COUNT(*) FROM support_request_messages m
                        WHERE m.tenant_key = f.tenant_key AND m.request_id = f.id AND m.kind = 'REPLY') AS reply_count,
                       ${Paging.TOTAL}
                FROM support_requests f
                JOIN tenants t ON t.id = f.tenant_key
                LEFT JOIN users u ON u.id = f.created_by
                LEFT JOIN support_connections sc ON sc.id = f.handler_connection
                $where
                ORDER BY f.created_at DESC, f.id
                ${Paging.LIMIT_OFFSET}
                """,
            ).also { q ->
                query.status?.let { q.bind("status", it.name) }
                query.tenant?.let { q.bind("tenant", it) }
                query.priority?.let { q.bind("priority", it.name) }
            }
        }
    }
}

/** A tenant the desk's inbox can be filtered on, by its [key] as the filter takes it. */
data class SupportInboxTenant(val key: String, val name: String)

/**
 * The tenants with support requests, by name: what the inbox's tenant filter offers. Every tenant of
 * the installation, as the inbox shows them all, not only those the reader is a member of.
 */
class ListSupportInboxTenants :
    Query<List<SupportInboxTenant>>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class ListSupportInboxTenantsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListSupportInboxTenants, List<SupportInboxTenant>> {
    override fun handle(query: ListSupportInboxTenants): List<SupportInboxTenant> = jdbi.withHandleUnchecked { handle ->
        handle.createQuery(
            "SELECT t.id, t.name FROM tenants t WHERE EXISTS (SELECT 1 FROM support_requests f WHERE f.tenant_key = t.id) ORDER BY t.name, t.id",
        )
            .map { rs, _ -> SupportInboxTenant(rs.getString("id"), rs.getString("name")) }
            .list()
    }
}

/** A message on the desk's view of a request: a reply, or an internal [note] only the desk sees. */
data class SupportDeskMessage(
    val id: UUID,
    val note: Boolean,
    val authorName: String,
    val body: String,
    /** The connection it came from; null when written in this installation. */
    val fromConnectionName: String?,
    val createdAt: OffsetDateTime,
)

/** A screenshot on the desk's view of a request. */
data class SupportDeskAsset(val id: SupportRequestAssetKey, val filename: String?)

/** A connection the desk can pass a request on to. */
data class SupportEscalationTarget(val connectionId: UUID, val name: String)

/**
 * The desk's view of one request: the request, its replies and notes in the order they were written,
 * its screenshots, and whether it can be passed on.
 *
 * @property escalationTargets where it can go now; empty with [escalationBlocked] saying why, or
 *   because a connection already handles it.
 */
data class SupportInboxRequest(
    val request: SupportRequest,
    val tenantName: String,
    val createdByName: String?,
    val handlerName: String?,
    val messages: List<SupportDeskMessage>,
    val assets: List<SupportDeskAsset>,
    val escalationTargets: List<SupportEscalationTarget>,
    val escalationBlocked: String?,
) {
    /** The inbox handles it, so the desk sets its status and priority. */
    val deskSetsStatus: Boolean get() = request.handledHere

    /** It can be passed on now: the inbox handles it and a connection takes it. */
    val canEscalate: Boolean get() = request.handledHere && escalationTargets.isNotEmpty()
}

/** One request as the support desk sees it; null when it does not exist. */
data class GetSupportInboxRequest(
    val tenant: TenantKey,
    val request: SupportRequestKey,
) : Query<SupportInboxRequest?>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class GetSupportInboxRequestHandler(
    private val jdbi: Jdbi,
    private val port: SupportRequestSyncPort,
) : QueryHandler<GetSupportInboxRequest, SupportInboxRequest?> {
    override fun handle(query: GetSupportInboxRequest): SupportInboxRequest? = jdbi.withHandleUnchecked { handle ->
        val request = handle.createQuery("$SELECT_SUPPORT_REQUEST WHERE f.tenant_key = :tenant AND f.id = :id")
            .bind("tenant", query.tenant)
            .bind("id", query.request.value)
            .mapTo(SupportRequest::class.java)
            .findOne()
            .orElse(null) ?: return@withHandleUnchecked null
        val facts = handle.createQuery(
            """
            SELECT t.name AS tenant_name, u.display_name AS created_by_name, sc.name AS handler_name,
                   f.sync_consent_id IS NOT NULL AS submitted_under_grant, ${consentInForce("f.tenant_key")} AS sharing_now
            FROM support_requests f
            JOIN tenants t ON t.id = f.tenant_key
            LEFT JOIN users u ON u.id = f.created_by
            LEFT JOIN support_connections sc ON sc.id = f.handler_connection
            WHERE f.tenant_key = :tenant AND f.id = :id
            """,
        )
            .bind("tenant", query.tenant)
            .bind("id", query.request.value)
            .map { rs, _ ->
                RequestFacts(rs.getString("tenant_name"), rs.getString("created_by_name"), rs.getString("handler_name"), rs.getBoolean("submitted_under_grant"), rs.getBoolean("sharing_now"))
            }
            .one()
        val messages = handle.createQuery(
            """
            SELECT m.id, m.kind, m.author_name, m.body, sc.name AS from_connection_name, m.created_at
            FROM support_request_messages m
            LEFT JOIN support_connections sc ON sc.id = m.from_connection
            WHERE m.tenant_key = :tenant AND m.request_id = :id AND m.kind IN ('REPLY', 'NOTE')
            ORDER BY m.seq
            """,
        )
            .bind("tenant", query.tenant)
            .bind("id", query.request.value)
            .map { rs, _ ->
                SupportDeskMessage(
                    id = rs.getObject("id", UUID::class.java),
                    note = rs.getString("kind") == "NOTE",
                    authorName = rs.getString("author_name"),
                    body = rs.getString("body"),
                    fromConnectionName = rs.getString("from_connection_name"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                )
            }
            .list()
        val assets = handle.createQuery("SELECT id, filename FROM support_request_assets WHERE tenant_key = :tenant AND request_id = :id ORDER BY created_at, id")
            .bind("tenant", query.tenant)
            .bind("id", query.request.value)
            .map { rs, _ -> SupportDeskAsset(SupportRequestAssetKey.of(rs.getObject("id", UUID::class.java)), rs.getString("filename")) }
            .list()
        val targets = choices(handle, port).map { SupportEscalationTarget(it.connectionId, it.name) }
        val blocked = when {
            !request.handledHere -> null
            targets.isEmpty() -> "No support connection takes support requests now: connect to Epistola support on the platform's Epistola support page."
            !facts.submittedUnderGrant -> "${facts.tenantName} did not share support requests when this one was submitted, so it stays in this installation."
            !facts.sharingNow -> "${facts.tenantName} does not share support requests now; its tenant administrator can change that on its Support page."
            else -> null
        }
        SupportInboxRequest(
            request = request,
            tenantName = facts.tenantName,
            createdByName = facts.createdByName,
            handlerName = facts.handlerName,
            messages = messages,
            assets = assets,
            escalationTargets = if (request.handledHere && blocked == null) targets else emptyList(),
            escalationBlocked = blocked,
        )
    }

    private class RequestFacts(val tenantName: String, val createdByName: String?, val handlerName: String?, val submittedUnderGrant: Boolean, val sharingNow: Boolean)
}

/** A screenshot's bytes for the support desk; null when it does not exist. */
data class GetSupportDeskAssetContent(
    val tenant: TenantKey,
    val request: SupportRequestKey,
    val asset: SupportRequestAssetKey,
) : Query<SupportRequestAssetContent?>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class GetSupportDeskAssetContentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetSupportDeskAssetContent, SupportRequestAssetContent?> {
    override fun handle(query: GetSupportDeskAssetContent): SupportRequestAssetContent? = jdbi.withHandleUnchecked { handle ->
        handle.createQuery("SELECT content, content_type, filename FROM support_request_assets WHERE tenant_key = :tenant AND request_id = :request AND id = :id")
            .bind("tenant", query.tenant)
            .bind("request", query.request.value)
            .bind("id", query.asset.value)
            .map { rs, _ -> SupportRequestAssetContent(rs.getBytes("content"), rs.getString("content_type"), rs.getString("filename")) }
            .findOne()
            .orElse(null)
    }
}

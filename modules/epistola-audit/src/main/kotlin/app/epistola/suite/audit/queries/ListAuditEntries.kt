package app.epistola.suite.audit.queries

import app.epistola.suite.audit.AuditEntry
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** Which way to page from the cursor: toward older rows, or toward newer rows. */
enum class AuditPageDirection { OLDER, NEWER }

/**
 * Lists `audit_log` rows for a tenant's Audit viewer using keyset (cursor)
 * pagination, mirroring [app.epistola.suite.logs.queries.ListApplicationLogs].
 *
 * Scope: the tenant's own rows **plus system rows that carry no tenant**
 * (`tenant_key = :tenantId OR tenant_key IS NULL`).
 *
 * Results are always returned **newest-first**, regardless of [direction]. The
 * cursor is the `(occurred_at, id)` of the edge row already shown; `id` breaks
 * ties. With no cursor the query returns the newest [limit] rows.
 *
 * The acting user's display name is resolved via a LEFT JOIN to `users` so the
 * viewer is readable; the audit table itself stays PII-free, and a missing user
 * (e.g. after erasure) simply yields a null name.
 *
 * @property tenantId Tenant whose audit rows (plus system rows) to list
 * @property action Optional exact action (command/query name) filter
 * @property actorUserId Optional exact actor filter
 * @property operation Optional exact operation filter ("WRITE" | "READ")
 * @property outcome Optional exact outcome filter ("SUCCESS" | "FAILURE")
 * @property from Optional inclusive lower bound on `occurred_at`
 * @property to Optional inclusive upper bound on `occurred_at`
 * @property limit Maximum number of results (default 20)
 * @property direction Page toward older or newer rows relative to the cursor
 * @property cursorOccurredAt `occurred_at` of the edge row already shown (null = no cursor)
 * @property cursorId `id` of the edge row already shown (tie-breaker)
 */
data class ListAuditEntries(
    val tenantId: TenantKey,
    val action: String? = null,
    val actorUserId: UUID? = null,
    val operation: String? = null,
    val outcome: String? = null,
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val limit: Int = 20,
    val direction: AuditPageDirection = AuditPageDirection.OLDER,
    val cursorOccurredAt: OffsetDateTime? = null,
    val cursorId: UUID? = null,
) : Query<List<AuditEntry>>,
    RequiresPermission {
    override val permission get() = Permission.AUDIT_VIEW
    override val tenantKey get() = tenantId

    val hasCursor: Boolean get() = cursorOccurredAt != null && cursorId != null
}

@Component
class ListAuditEntriesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListAuditEntries, List<AuditEntry>> {

    override fun handle(query: ListAuditEntries): List<AuditEntry> = jdbi.withHandle<List<AuditEntry>, Exception> { handle ->
        val ascending = query.direction == AuditPageDirection.NEWER

        val sql = StringBuilder(
            """
            SELECT a.id, a.occurred_at, a.tenant_key, a.actor_user_id, u.display_name AS actor_display_name,
                   a.action, a.operation, a.entity_type, a.entity_id, a.outcome, a.error_code, a.instance_id
            FROM audit_log a
            LEFT JOIN users u ON u.id = a.actor_user_id
            WHERE (a.tenant_key = :tenantId OR a.tenant_key IS NULL)
            """,
        )

        if (query.action != null) sql.append(" AND a.action = :action")
        if (query.actorUserId != null) sql.append(" AND a.actor_user_id = :actorUserId")
        if (query.operation != null) sql.append(" AND a.operation = :operation")
        if (query.outcome != null) sql.append(" AND a.outcome = :outcome")
        if (query.from != null) sql.append(" AND a.occurred_at >= :from")
        if (query.to != null) sql.append(" AND a.occurred_at <= :to")
        if (query.hasCursor) {
            // Row-value keyset comparison on (occurred_at, id) — stable across ties.
            sql.append(
                if (ascending) {
                    " AND (a.occurred_at, a.id) > (:cursorTs, :cursorId)"
                } else {
                    " AND (a.occurred_at, a.id) < (:cursorTs, :cursorId)"
                },
            )
        }
        sql.append(if (ascending) " ORDER BY a.occurred_at ASC, a.id ASC" else " ORDER BY a.occurred_at DESC, a.id DESC")
        sql.append(" LIMIT :limit")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)

        if (query.action != null) q.bind("action", query.action)
        if (query.actorUserId != null) q.bind("actorUserId", query.actorUserId)
        if (query.operation != null) q.bind("operation", query.operation)
        if (query.outcome != null) q.bind("outcome", query.outcome)
        if (query.from != null) q.bind("from", query.from)
        if (query.to != null) q.bind("to", query.to)
        if (query.hasCursor) {
            q.bind("cursorTs", query.cursorOccurredAt)
            q.bind("cursorId", query.cursorId)
        }

        val rows = q.map { rs, _ ->
            AuditEntry(
                id = rs.getObject("id", UUID::class.java),
                occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java),
                tenantKey = rs.getString("tenant_key")?.let { TenantKey.of(it) },
                actorUserId = rs.getObject("actor_user_id", UUID::class.java),
                actorDisplayName = rs.getString("actor_display_name"),
                action = rs.getString("action"),
                operation = rs.getString("operation"),
                entityType = rs.getString("entity_type"),
                entityId = rs.getString("entity_id"),
                outcome = rs.getString("outcome"),
                errorCode = rs.getString("error_code"),
                instanceId = rs.getString("instance_id"),
            )
        }.list()

        // Always hand back newest-first; the ascending (NEWER) scan came back oldest-first.
        if (ascending) rows.reversed() else rows
    }
}

package app.epistola.suite.logs.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.logs.ApplicationLogEntry
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** Which way to page from the cursor: toward older rows, or toward newer rows. */
enum class LogPageDirection { OLDER, NEWER }

/**
 * Lists application log rows for a tenant's Logs viewer using keyset (cursor)
 * pagination so the UI can incrementally load older or newer entries without
 * offset drift as new rows arrive.
 *
 * Scope: the tenant's own rows **plus system rows that carry no tenant**
 * (`tenant_key = :tenantId OR tenant_key IS NULL`).
 *
 * Results are always returned **newest-first**, regardless of [direction], so
 * the UI renders them uniformly and decides where to place them (append below
 * for [LogPageDirection.OLDER], prepend above for [LogPageDirection.NEWER]).
 *
 * The cursor is the `(occurred_at, id)` of the edge row already shown; `id`
 * breaks ties when many events share a millisecond. With no cursor the query
 * returns the newest [limit] rows.
 *
 * @property tenantId Tenant whose logs (plus system logs) to list
 * @property level Optional exact level filter (e.g. "ERROR")
 * @property logger Optional case-insensitive substring match on the logger name
 * @property search Optional case-insensitive substring match on the message
 * @property from Optional inclusive lower bound on `occurred_at`
 * @property to Optional inclusive upper bound on `occurred_at`
 * @property limit Maximum number of results (default 20)
 * @property direction Page toward older or newer rows relative to the cursor
 * @property cursorOccurredAt `occurred_at` of the edge row already shown (null = no cursor)
 * @property cursorId `id` of the edge row already shown (tie-breaker)
 */
data class ListApplicationLogs(
    val tenantId: TenantKey,
    val level: String? = null,
    val logger: String? = null,
    val search: String? = null,
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val limit: Int = 20,
    val direction: LogPageDirection = LogPageDirection.OLDER,
    val cursorOccurredAt: OffsetDateTime? = null,
    val cursorId: UUID? = null,
) : Query<List<ApplicationLogEntry>>,
    RequiresPermission {
    override val permission get() = Permission.DIAGNOSTICS_VIEW
    override val tenantKey get() = tenantId

    val hasCursor: Boolean get() = cursorOccurredAt != null && cursorId != null
}

@Component
class ListApplicationLogsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListApplicationLogs, List<ApplicationLogEntry>> {

    override fun handle(query: ListApplicationLogs): List<ApplicationLogEntry> = jdbi.withHandle<List<ApplicationLogEntry>, Exception> { handle ->
        val ascending = query.direction == LogPageDirection.NEWER

        val sql = StringBuilder(
            """
            SELECT id, occurred_at, level, logger, message, thread, instance_id,
                   tenant_key, trace_id, span_id, exception
            FROM application_log
            WHERE (tenant_key = :tenantId OR tenant_key IS NULL)
            """,
        )

        if (query.level != null) sql.append(" AND level = :level")
        if (query.logger != null) sql.append(" AND logger ILIKE :loggerPattern")
        if (query.search != null) sql.append(" AND message ILIKE :searchPattern")
        if (query.from != null) sql.append(" AND occurred_at >= :from")
        if (query.to != null) sql.append(" AND occurred_at <= :to")
        if (query.hasCursor) {
            // Row-value keyset comparison on (occurred_at, id) — stable across ties.
            sql.append(
                if (ascending) {
                    " AND (occurred_at, id) > (:cursorTs, :cursorId)"
                } else {
                    " AND (occurred_at, id) < (:cursorTs, :cursorId)"
                },
            )
        }
        sql.append(if (ascending) " ORDER BY occurred_at ASC, id ASC" else " ORDER BY occurred_at DESC, id DESC")
        sql.append(" LIMIT :limit")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)

        if (query.level != null) q.bind("level", query.level)
        if (query.logger != null) q.bind("loggerPattern", "%${query.logger}%")
        if (query.search != null) q.bind("searchPattern", "%${query.search}%")
        if (query.from != null) q.bind("from", query.from)
        if (query.to != null) q.bind("to", query.to)
        if (query.hasCursor) {
            q.bind("cursorTs", query.cursorOccurredAt)
            q.bind("cursorId", query.cursorId)
        }

        val rows = q.map { rs, _ ->
            ApplicationLogEntry(
                id = rs.getObject("id", UUID::class.java),
                occurredAt = rs.getObject("occurred_at", OffsetDateTime::class.java),
                level = rs.getString("level"),
                logger = rs.getString("logger"),
                message = rs.getString("message"),
                thread = rs.getString("thread"),
                instanceId = rs.getString("instance_id"),
                tenantKey = rs.getString("tenant_key")?.let { TenantKey.of(it) },
                traceId = rs.getString("trace_id"),
                spanId = rs.getString("span_id"),
                exception = rs.getString("exception"),
            )
        }.list()

        // Always hand back newest-first; the ascending (NEWER) scan came back oldest-first.
        if (ascending) rows.reversed() else rows
    }
}

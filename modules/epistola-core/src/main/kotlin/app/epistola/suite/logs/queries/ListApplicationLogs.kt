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

/**
 * Lists application log rows for a tenant's Logs viewer.
 *
 * Scope: the tenant's own rows **plus system rows that carry no tenant**
 * (`tenant_key = :tenantId OR tenant_key IS NULL`) — operators need to see
 * background/system activity alongside their tenant's activity. Newest first,
 * with optional level / logger / time-range filtering and offset pagination.
 *
 * @property tenantId Tenant whose logs (plus system logs) to list
 * @property level Optional exact level filter (e.g. "ERROR")
 * @property logger Optional case-insensitive substring match on the logger name
 * @property from Optional inclusive lower bound on `occurred_at`
 * @property to Optional exclusive upper bound on `occurred_at`
 * @property limit Maximum number of results (default 100)
 * @property offset Pagination offset (default 0)
 */
data class ListApplicationLogs(
    val tenantId: TenantKey,
    val level: String? = null,
    val logger: String? = null,
    val from: OffsetDateTime? = null,
    val to: OffsetDateTime? = null,
    val limit: Int = 100,
    val offset: Int = 0,
) : Query<List<ApplicationLogEntry>>,
    RequiresPermission {
    override val permission get() = Permission.TENANT_SETTINGS
    override val tenantKey get() = tenantId
}

@Component
class ListApplicationLogsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListApplicationLogs, List<ApplicationLogEntry>> {

    override fun handle(query: ListApplicationLogs): List<ApplicationLogEntry> = jdbi.withHandle<List<ApplicationLogEntry>, Exception> { handle ->
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
        if (query.from != null) sql.append(" AND occurred_at >= :from")
        if (query.to != null) sql.append(" AND occurred_at < :to")
        sql.append(" ORDER BY occurred_at DESC LIMIT :limit OFFSET :offset")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)
            .bind("offset", query.offset)

        if (query.level != null) q.bind("level", query.level)
        if (query.logger != null) q.bind("loggerPattern", "%${query.logger}%")
        if (query.from != null) q.bind("from", query.from)
        if (query.to != null) q.bind("to", query.to)

        q.map { rs, _ ->
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
    }
}

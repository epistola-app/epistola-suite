// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.logs.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * One log line of an excerpt. [messagePattern] is the message as written in the code, before its
 * values were filled in: the message itself for a line without values. (A line stored before patterns
 * were kept never reaches an excerpt: it has no user either.)
 */
data class TenantLogLine(
    val occurredAt: OffsetDateTime,
    val level: String,
    val logger: String,
    val message: String,
    val messagePattern: String?,
    val exception: String?,
    val traceId: String,
)

/** The lines of an excerpt, oldest first; [truncated] when there were more than the limit. */
data class TenantLogExcerpt(val lines: List<TenantLogLine>, val truncated: Boolean)

/**
 * The application log lines of [tenantKey] written for the requests [traceIds] name, oldest first, at
 * most [limit] of them: what someone reporting a problem attaches to it. Only the tenant's own lines
 * of the caller's own requests: trace ids are no secret (a client may choose its own), so naming a
 * colleague's finds nothing, and reading everyone's lines stays `DIAGNOSTICS_VIEW`'s. Gated on
 * [Permission.FEEDBACK_SUBMIT]: it is read for a report, by whoever submits one.
 */
data class GetTenantLogExcerpt(
    override val tenantKey: TenantKey,
    val traceIds: List<String>,
    val limit: Int = DEFAULT_LIMIT,
) : Query<TenantLogExcerpt>,
    RequiresPermission {
    override val permission get() = Permission.FEEDBACK_SUBMIT

    init {
        validate("traceIds", traceIds.size <= MAX_TRACES) { "At most $MAX_TRACES traces" }
        validate("traceIds", traceIds.all { TRACE_ID.matches(it) }) { "A trace id is 32 lowercase hex digits" }
        validate("limit", limit in 1..MAX_LIMIT) { "The limit is between 1 and $MAX_LIMIT" }
    }

    companion object {
        const val MAX_TRACES = 50
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 1_000
        private val TRACE_ID = Regex("[0-9a-f]{32}")
    }
}

@Component
class GetTenantLogExcerptHandler(private val jdbi: Jdbi) : QueryHandler<GetTenantLogExcerpt, TenantLogExcerpt> {
    override fun handle(query: GetTenantLogExcerpt): TenantLogExcerpt {
        if (query.traceIds.isEmpty()) return TenantLogExcerpt(emptyList(), truncated = false)
        val lines = jdbi.withHandle<List<TenantLogLine>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT occurred_at, level, logger, message, message_pattern, exception, trace_id
                FROM application_log
                WHERE trace_id = ANY(:traces) AND tenant_key = :tenant AND user_id = :caller
                ORDER BY occurred_at, id
                LIMIT :limit
                """,
            )
                .bindArray("traces", String::class.java, query.traceIds)
                .bind("tenant", query.tenantKey.value)
                .bind("caller", SecurityContext.current().userId.value)
                .bind("limit", query.limit + 1)
                .map { rs, _ ->
                    TenantLogLine(
                        rs.getObject("occurred_at", OffsetDateTime::class.java),
                        rs.getString("level"),
                        rs.getString("logger"),
                        rs.getString("message"),
                        rs.getString("message_pattern") ?: rs.getString("message"),
                        rs.getString("exception"),
                        rs.getString("trace_id"),
                    )
                }
                .list()
        }
        return TenantLogExcerpt(lines.take(query.limit), truncated = lines.size > query.limit)
    }
}

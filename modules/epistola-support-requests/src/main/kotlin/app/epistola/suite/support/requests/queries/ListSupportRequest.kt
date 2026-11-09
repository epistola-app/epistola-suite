// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.requests.SupportRequestCategory
import app.epistola.suite.support.requests.SupportRequestStatus
import app.epistola.suite.support.requests.SupportRequestSummary
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

data class ListSupportRequest(
    override val tenantKey: TenantKey,
    val status: SupportRequestStatus? = null,
    val category: SupportRequestCategory? = null,
    val sourceUrl: String? = null,
) : Query<List<SupportRequestSummary>>,
    RequiresPermission {
    override val permission get() = Permission.FEEDBACK_SUBMIT
}

@Component
class ListSupportRequestHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListSupportRequest, List<SupportRequestSummary>> {
    override fun handle(query: ListSupportRequest): List<SupportRequestSummary> = jdbi.withHandleUnchecked { handle ->
        val conditions = mutableListOf("f.tenant_key = :tenantKey")
        // Someone who does not manage support requests sees only their own: as for opening one.
        val principal = SecurityContext.current()
        val ownOnly = !principal.hasPermission(query.tenantKey, Permission.FEEDBACK_MANAGE)
        if (ownOnly) conditions.add("f.created_by = :caller")
        if (query.status != null) {
            conditions.add("f.status = :status")
        } else if (query.sourceUrl != null) {
            conditions.add("f.status IN ('OPEN', 'IN_PROGRESS')")
        }
        if (query.category != null) conditions.add("f.category = :category")
        if (query.sourceUrl != null) {
            conditions.add(
                """(f.source_url LIKE '%' || :pathname
                    OR f.source_url LIKE '%' || :pathname || '?%'
                    OR f.source_url LIKE '%' || :pathname || '#%')""",
            )
        }

        val where = conditions.joinToString(" AND ")

        val q = handle.createQuery(
            """
            SELECT f.id, f.tenant_key, f.title, f.category, f.status, f.priority,
                   f.source_url,
                   u.display_name AS created_by_name,
                   COALESCE(c.comment_count, 0) AS comment_count,
                   f.created_at, f.updated_at
            FROM support_requests f
            LEFT JOIN users u ON u.id = f.created_by
            LEFT JOIN LATERAL (
                SELECT COUNT(*) AS comment_count
                FROM support_request_messages fc
                WHERE fc.tenant_key = f.tenant_key AND fc.request_id = f.id AND fc.kind = 'REPLY'
            ) c ON true
            WHERE $where
            ORDER BY f.created_at DESC
            """,
        )
            .bind("tenantKey", query.tenantKey)

        if (query.status != null) q.bind("status", query.status.name)
        if (query.category != null) q.bind("category", query.category.name)
        if (query.sourceUrl != null) q.bind("pathname", query.sourceUrl)
        if (ownOnly) q.bind("caller", principal.userId.value)

        q.mapTo(SupportRequestSummary::class.java).list()
    }
}

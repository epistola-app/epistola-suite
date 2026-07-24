// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Total number of generation jobs matching the same filter as [ListGenerationJobs],
 * ignoring pagination — the `totalElements` for the paginated REST list envelope.
 */
data class CountGenerationJobs(
    val tenantId: TenantKey,
    val status: RequestStatus? = null,
) : Query<Long>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

@Component
class CountGenerationJobsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountGenerationJobs, Long> {
    override fun handle(query: CountGenerationJobs): Long = jdbi.withHandle<Long, Exception> { handle ->
        val sql = StringBuilder("SELECT COUNT(*) FROM document_generation_requests WHERE tenant_key = :tenantId")
        if (query.status != null) {
            sql.append(" AND status = :status")
        }
        val q = handle.createQuery(sql.toString()).bind("tenantId", query.tenantId)
        if (query.status != null) {
            q.bind("status", query.status.name)
        }
        q.mapTo<Long>().one()
    }
}

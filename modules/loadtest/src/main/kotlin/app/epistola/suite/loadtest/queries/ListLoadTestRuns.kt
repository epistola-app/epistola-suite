// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.loadtest.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Query to list load test runs for a tenant.
 *
 * @property tenantId Tenant that owns the load tests
 * @property limit Maximum number of results (default: 50, max: 200)
 * @return List of load test runs, ordered by created_at descending (most recent first)
 */
data class ListLoadTestRuns(
    val tenantId: TenantKey,
    val limit: Int = 50,
) : Query<List<LoadTestRun>>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId

    init {
        require(limit in 1..200) {
            "Limit must be between 1 and 200, got $limit"
        }
    }
}

@Component
class ListLoadTestRunsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListLoadTestRuns, List<LoadTestRun>> {
    override fun handle(query: ListLoadTestRuns): List<LoadTestRun> = jdbi.withHandle<List<LoadTestRun>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.id, r.batch_id, r.tenant_key, template.catalog_key, template.id AS template_key,
                   r.variant_key, r.version_key, r.environment_key,
                   r.target_count, r.concurrency_level, r.test_data, r.status, r.claimed_by, r.claimed_at,
                   r.completed_count, r.failed_count, r.total_duration_ms, r.avg_response_time_ms,
                   r.min_response_time_ms, r.max_response_time_ms, r.p50_response_time_ms,
                   r.p95_response_time_ms, r.p99_response_time_ms, r.requests_per_second,
                   r.success_rate_percent, r.error_summary, r.metrics, r.created_at, r.started_at, r.completed_at
            FROM load_test_runs r
            JOIN document_templates template ON template.tenant_key = r.tenant_key AND template.resource_id = r.template_resource_id
            WHERE r.tenant_key = :tenantId
            ORDER BY r.created_at DESC
            LIMIT :limit
            """,
        )
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)
            .mapTo<LoadTestRun>()
            .list()
    }
}

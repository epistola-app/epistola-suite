package app.epistola.suite.loadtest.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
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
    val tenantId: TenantId,
    val limit: Int = 50,
) : Query<List<LoadTestRun>> {
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
            SELECT id, batch_id, tenant_id, template_id, variant_id, version_id, environment_id,
                   target_count, concurrency_level, test_data, status, claimed_by, claimed_at,
                   completed_count, failed_count, total_duration_ms, avg_response_time_ms,
                   min_response_time_ms, max_response_time_ms, p50_response_time_ms,
                   p95_response_time_ms, p99_response_time_ms, requests_per_second,
                   success_rate_percent, error_summary, metrics, created_at, started_at, completed_at
            FROM load_test_runs
            WHERE tenant_id = :tenantId
            ORDER BY created_at DESC
            LIMIT :limit
            """,
        )
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)
            .mapTo<LoadTestRun>()
            .list()
    }
}

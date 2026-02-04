package app.epistola.suite.loadtest.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.loadtest.model.LoadTestRun
import app.epistola.suite.loadtest.model.LoadTestRunId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Query to get a single load test run by ID.
 *
 * @property tenantId Tenant that owns the load test
 * @property runId Load test run ID
 * @return LoadTestRun if found, null otherwise
 */
data class GetLoadTestRun(
    val tenantId: TenantId,
    val runId: LoadTestRunId,
) : Query<LoadTestRun?>

@Component
class GetLoadTestRunHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetLoadTestRun, LoadTestRun?> {
    override fun handle(query: GetLoadTestRun): LoadTestRun? = jdbi.withHandle<LoadTestRun?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, template_id, variant_id, version_id, environment_id,
                   target_count, concurrency_level, test_data, status, claimed_by, claimed_at,
                   completed_count, failed_count, total_duration_ms, avg_response_time_ms,
                   min_response_time_ms, max_response_time_ms, p50_response_time_ms,
                   p95_response_time_ms, p99_response_time_ms, requests_per_second,
                   success_rate_percent, error_summary, created_at, started_at, completed_at
            FROM load_test_runs
            WHERE id = :runId
              AND tenant_id = :tenantId
            """,
        )
            .bind("runId", query.runId)
            .bind("tenantId", query.tenantId)
            .mapTo<LoadTestRun>()
            .findOne()
            .orElse(null)
    }
}

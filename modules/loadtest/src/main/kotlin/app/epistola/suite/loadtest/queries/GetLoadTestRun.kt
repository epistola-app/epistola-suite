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
            SELECT
                r.id,
                r.batch_id,
                r.tenant_id,
                r.template_id,
                r.variant_id,
                r.version_id,
                r.environment_id,
                r.target_count,
                r.concurrency_level,
                r.test_data,
                r.status,
                r.claimed_by,
                r.claimed_at,
                r.total_duration_ms,
                r.avg_response_time_ms,
                r.min_response_time_ms,
                r.max_response_time_ms,
                r.p50_response_time_ms,
                r.p95_response_time_ms,
                r.p99_response_time_ms,
                r.requests_per_second,
                r.success_rate_percent,
                r.error_summary,
                r.metrics,
                r.created_at,
                r.started_at,
                r.completed_at,
                -- Calculate counts: use final counts if completed, else calculate from source
                CASE
                    WHEN r.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN r.completed_count
                    ELSE COALESCE((
                        SELECT COUNT(*)
                        FROM document_generation_requests
                        WHERE batch_id = r.batch_id AND status = 'COMPLETED'
                    ), 0)
                END as completed_count,
                CASE
                    WHEN r.status IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN r.failed_count
                    ELSE COALESCE((
                        SELECT COUNT(*)
                        FROM document_generation_requests
                        WHERE batch_id = r.batch_id AND status = 'FAILED'
                    ), 0)
                END as failed_count
            FROM load_test_runs r
            WHERE r.id = :runId
              AND r.tenant_id = :tenantId
            """,
        )
            .bind("runId", query.runId)
            .bind("tenantId", query.tenantId)
            .mapTo<LoadTestRun>()
            .findOne()
            .orElse(null)
    }
}

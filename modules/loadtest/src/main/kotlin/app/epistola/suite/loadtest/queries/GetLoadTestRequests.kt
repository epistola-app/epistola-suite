package app.epistola.suite.loadtest.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.loadtest.model.LoadTestRequest
import app.epistola.suite.loadtest.model.LoadTestRunId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Query to get detailed request log for a load test run.
 *
 * @property tenantId Tenant that owns the load test
 * @property runId Load test run ID
 * @property offset Number of requests to skip (for pagination)
 * @property limit Maximum number of requests to return (default: 100, max: 1000)
 * @return List of load test requests, ordered by sequence number
 */
data class GetLoadTestRequests(
    val tenantId: TenantId,
    val runId: LoadTestRunId,
    val offset: Int = 0,
    val limit: Int = 100,
) : Query<List<LoadTestRequest>> {
    init {
        require(offset >= 0) {
            "Offset must be non-negative, got $offset"
        }
        require(limit in 1..1000) {
            "Limit must be between 1 and 1000, got $limit"
        }
    }
}

@Component
class GetLoadTestRequestsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetLoadTestRequests, List<LoadTestRequest>> {
    override fun handle(query: GetLoadTestRequests): List<LoadTestRequest> = jdbi.withHandle<List<LoadTestRequest>, Exception> { handle ->
        // First, verify the run belongs to the tenant
        val runExists = handle.createQuery(
            """
            SELECT EXISTS (
                SELECT 1 FROM load_test_runs
                WHERE id = :runId
                  AND tenant_id = :tenantId
            )
            """,
        )
            .bind("runId", query.runId)
            .bind("tenantId", query.tenantId)
            .mapTo<Boolean>()
            .one()

        if (!runExists) {
            return@withHandle emptyList()
        }

        // Fetch requests
        handle.createQuery(
            """
            SELECT id, run_id, sequence_number, started_at, completed_at, duration_ms,
                   success, error_message, error_type, document_id
            FROM load_test_requests
            WHERE run_id = :runId
            ORDER BY sequence_number ASC
            LIMIT :limit
            OFFSET :offset
            """,
        )
            .bind("runId", query.runId)
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo<LoadTestRequest>()
            .list()
    }
}

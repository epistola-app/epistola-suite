package app.epistola.suite.loadtest.queries

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.loadtest.model.LoadTestRequest
import app.epistola.suite.loadtest.model.LoadTestRequestId
import app.epistola.suite.loadtest.model.LoadTestRunId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

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
        // First, verify the run belongs to the tenant and get batch_id
        val batchId = handle.createQuery(
            """
            SELECT batch_id
            FROM load_test_runs
            WHERE id = :runId
              AND tenant_id = :tenantId
            """,
        )
            .bind("runId", query.runId)
            .bind("tenantId", query.tenantId)
            .mapTo<java.util.UUID>()
            .findOne()
            .orElse(null)

        if (batchId == null) {
            return@withHandle emptyList()
        }

        // Fetch requests from document_generation_requests (single source of truth)
        handle.createQuery(
            """
            SELECT
                id,
                created_at as started_at,
                completed_at,
                CASE
                    WHEN started_at IS NOT NULL AND completed_at IS NOT NULL
                    THEN (EXTRACT(EPOCH FROM (completed_at - started_at)) * 1000)::BIGINT
                    ELSE NULL
                END as duration_ms,
                CASE WHEN status = 'COMPLETED' THEN true ELSE false END as success,
                error_message,
                CASE
                    WHEN status != 'COMPLETED' AND error_message IS NOT NULL THEN
                        CASE
                            WHEN error_message ILIKE '%validation%' THEN 'VALIDATION'
                            WHEN error_message ILIKE '%timeout%' THEN 'TIMEOUT'
                            WHEN error_message ILIKE '%not found%' THEN 'CONFIGURATION'
                            ELSE 'GENERATION'
                        END
                    ELSE NULL
                END as error_type,
                document_id,
                ROW_NUMBER() OVER (ORDER BY created_at) as sequence_number
            FROM document_generation_requests
            WHERE batch_id = :batchId
            ORDER BY created_at
            LIMIT :limit
            OFFSET :offset
            """,
        )
            .bind("batchId", batchId)
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .map { rs, _ ->
                LoadTestRequest(
                    id = LoadTestRequestId(rs.getObject("id", java.util.UUID::class.java)),
                    runId = query.runId,
                    sequenceNumber = rs.getInt("sequence_number"),
                    startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                    completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                    durationMs = rs.getObject("duration_ms", java.lang.Long::class.java)?.toLong(),
                    success = rs.getBoolean("success"),
                    errorMessage = rs.getString("error_message"),
                    errorType = rs.getString("error_type"),
                    documentId = rs.getObject("document_id", java.util.UUID::class.java)?.let { app.epistola.suite.common.ids.DocumentId.of(it) },
                )
            }
            .list()
    }
}

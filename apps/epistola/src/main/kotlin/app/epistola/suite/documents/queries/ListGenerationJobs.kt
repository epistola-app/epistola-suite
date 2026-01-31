package app.epistola.suite.documents.queries

import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.documents.model.RequestStatus
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Query to list generation jobs for a tenant.
 *
 * @property tenantId Tenant that owns the jobs
 * @property status Optional status filter
 * @property limit Maximum number of results (default: 50)
 * @property offset Pagination offset (default: 0)
 */
data class ListGenerationJobs(
    val tenantId: UUID,
    val status: RequestStatus? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<DocumentGenerationRequest>>

@Component
class ListGenerationJobsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListGenerationJobs, List<DocumentGenerationRequest>> {

    override fun handle(query: ListGenerationJobs): List<DocumentGenerationRequest> = jdbi.withHandle<List<DocumentGenerationRequest>, Exception> { handle ->
        val sql = StringBuilder(
            """
            SELECT id, tenant_id, job_type, status, claimed_by, claimed_at,
                   total_count, completed_count, failed_count, error_message,
                   created_at, started_at, completed_at, expires_at
            FROM document_generation_requests
            WHERE tenant_id = :tenantId
            """,
        )

        if (query.status != null) {
            sql.append(" AND status = :status")
        }

        sql.append(" ORDER BY created_at DESC")
        sql.append(" LIMIT :limit OFFSET :offset")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)
            .bind("offset", query.offset)

        if (query.status != null) {
            q.bind("status", query.status.name)
        }

        q.mapTo<DocumentGenerationRequest>()
            .list()
    }
}

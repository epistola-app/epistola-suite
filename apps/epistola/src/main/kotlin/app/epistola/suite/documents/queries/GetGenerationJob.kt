package app.epistola.suite.documents.queries

import app.epistola.suite.documents.model.DocumentGenerationItem
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Result of GetGenerationJob query.
 */
data class GenerationJobResult(
    val request: DocumentGenerationRequest,
    val items: List<DocumentGenerationItem>,
)

/**
 * Query to get a generation job with all its items.
 *
 * @property tenantId Tenant that owns the job
 * @property requestId The generation request ID
 */
data class GetGenerationJob(
    val tenantId: Long,
    val requestId: UUID,
) : Query<GenerationJobResult?>

@Component
class GetGenerationJobHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetGenerationJob, GenerationJobResult?> {

    override fun handle(query: GetGenerationJob): GenerationJobResult? = jdbi.withHandle<GenerationJobResult?, Exception> { handle ->
        // 1. Get request
        val request = handle.createQuery(
            """
            SELECT id, tenant_id, job_type, status, batch_job_execution_id,
                   total_count, completed_count, failed_count, error_message,
                   created_at, started_at, completed_at, expires_at
            FROM document_generation_requests
            WHERE id = :requestId
              AND tenant_id = :tenantId
            """,
        )
            .bind("requestId", query.requestId)
            .bind("tenantId", query.tenantId)
            .mapTo<DocumentGenerationRequest>()
            .findOne()
            .orElse(null) ?: return@withHandle null

        // 2. Get items
        val items = handle.createQuery(
            """
            SELECT id, request_id, template_id, variant_id, version_id, environment_id,
                   data, filename, status, error_message, document_id,
                   created_at, started_at, completed_at
            FROM document_generation_items
            WHERE request_id = :requestId
            ORDER BY created_at ASC
            """,
        )
            .bind("requestId", query.requestId)
            .mapTo<DocumentGenerationItem>()
            .list()

        GenerationJobResult(request, items)
    }
}

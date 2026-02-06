package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.GenerationRequestId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Result of GetGenerationJob query.
 *
 * In the flattened structure, each request IS a complete job (contains all data).
 * The `items` list contains a single element (the request itself) for API compatibility.
 */
data class GenerationJobResult(
    val request: DocumentGenerationRequest,
    val items: List<DocumentGenerationRequest>,
)

/**
 * Query to get a generation job.
 *
 * @property tenantId Tenant that owns the job
 * @property requestId The generation request ID
 */
data class GetGenerationJob(
    val tenantId: TenantId,
    val requestId: GenerationRequestId,
) : Query<GenerationJobResult?>

@Component
class GetGenerationJobHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetGenerationJob, GenerationJobResult?> {

    override fun handle(query: GetGenerationJob): GenerationJobResult? = jdbi.withHandle<GenerationJobResult?, Exception> { handle ->
        // Get request with all data (in flattened structure, request IS the item)
        val request = handle.createQuery(
            """
            SELECT id, batch_id, tenant_id, template_id, variant_id, version_id, environment_id,
                   data, filename, correlation_id, document_id, status, claimed_by, claimed_at,
                   error_message, created_at, started_at, completed_at, expires_at
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

        // For API compatibility, return request as both the job and a single-item list
        GenerationJobResult(request, listOf(request))
    }
}

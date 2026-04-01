package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.documents.model.DocumentGenerationRequest
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Query to list recently failed generation jobs for a tenant.
 *
 * @property tenantId Tenant that owns the jobs
 * @property limit Maximum number of results (default: 10)
 */
data class ListRecentFailedJobs(
    val tenantId: TenantKey,
    val limit: Int = 10,
) : Query<List<DocumentGenerationRequest>>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

@Component
class ListRecentFailedJobsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListRecentFailedJobs, List<DocumentGenerationRequest>> {

    override fun handle(query: ListRecentFailedJobs): List<DocumentGenerationRequest> = jdbi.withHandle<List<DocumentGenerationRequest>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, batch_id, tenant_key, template_key, variant_key, version_key, environment_key,
                   data, filename, correlation_key, document_key, status, claimed_by, claimed_at,
                   error_message, created_at, started_at, completed_at, expires_at
            FROM document_generation_requests
            WHERE tenant_key = :tenantId AND status = 'FAILED'
            ORDER BY completed_at DESC
            LIMIT :limit
            """,
        )
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)
            .mapTo<DocumentGenerationRequest>()
            .list()
    }
}

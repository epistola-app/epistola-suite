package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Query to retrieve aggregated generation statistics for a tenant.
 */
data class GetGenerationStats(
    val tenantId: TenantKey,
) : Query<GenerationStats>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetGenerationStatsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetGenerationStats, GenerationStats> {

    override fun handle(query: GetGenerationStats): GenerationStats = jdbi.withHandle<GenerationStats, Exception> { handle ->
        handle.createQuery(
            """
            SELECT COUNT(*) AS total,
                   COUNT(*) FILTER (WHERE status IN ('PENDING', 'IN_PROGRESS')) AS in_queue,
                   COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                   COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                   COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
            FROM document_generation_requests
            WHERE tenant_key = :tenantId
            """,
        )
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                GenerationStats(
                    totalGenerated = rs.getLong("total"),
                    inQueue = rs.getLong("in_queue"),
                    completed = rs.getLong("completed"),
                    failed = rs.getLong("failed"),
                    cancelled = rs.getLong("cancelled"),
                )
            }
            .one()
    }
}

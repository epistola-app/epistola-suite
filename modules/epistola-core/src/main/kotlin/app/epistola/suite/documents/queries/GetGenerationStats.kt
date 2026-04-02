package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Query to retrieve aggregated generation statistics for a tenant.
 *
 * @property tenantId Tenant to query
 * @property window Time window to aggregate over (default: 30 days). Enables partition pruning
 *   on the time-partitioned `document_generation_requests` table.
 */
data class GetGenerationStats(
    val tenantId: TenantKey,
    val window: Duration = Duration.ofDays(30),
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
              AND created_at >= now() - :window::interval
            """,
        )
            .bind("tenantId", query.tenantId)
            .bind("window", "${query.window.toSeconds()} seconds")
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

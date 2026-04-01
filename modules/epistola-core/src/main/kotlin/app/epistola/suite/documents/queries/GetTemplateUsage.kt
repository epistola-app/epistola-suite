package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Query to retrieve template usage frequency for a tenant.
 *
 * @property tenantId Tenant to query
 * @property limit Maximum number of results (default: 10)
 */
data class GetTemplateUsage(
    val tenantId: TenantKey,
    val limit: Int = 10,
) : Query<List<TemplateUsage>>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetTemplateUsageHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetTemplateUsage, List<TemplateUsage>> {

    override fun handle(query: GetTemplateUsage): List<TemplateUsage> = jdbi.withHandle<List<TemplateUsage>, Exception> { handle ->
        handle.createQuery(
            """
            SELECT template_key, variant_key, COUNT(*) AS count
            FROM document_generation_requests
            WHERE tenant_key = :tenantId
            GROUP BY template_key, variant_key
            ORDER BY count DESC
            LIMIT :limit
            """,
        )
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)
            .map { rs, _ ->
                TemplateUsage(
                    templateKey = TemplateKey.of(rs.getString("template_key")),
                    variantKey = VariantKey.of(rs.getString("variant_key")),
                    count = rs.getLong("count"),
                )
            }
            .list()
    }
}

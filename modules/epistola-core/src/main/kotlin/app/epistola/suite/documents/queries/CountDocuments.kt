package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Total number of documents matching the same filters as [ListDocuments], ignoring
 * pagination — the `totalElements` for the paginated REST list envelope.
 */
data class CountDocuments(
    val tenantId: TenantKey,
    val templateId: TemplateKey? = null,
    val correlationId: String? = null,
) : Query<Long>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

@Component
class CountDocumentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<CountDocuments, Long> {
    override fun handle(query: CountDocuments): Long = jdbi.withHandle<Long, Exception> { handle ->
        val sql = StringBuilder("SELECT COUNT(*) FROM documents WHERE tenant_key = :tenantId")
        if (query.templateId != null) {
            sql.append(" AND template_key = :templateId")
        }
        if (query.correlationId != null) {
            sql.append(" AND correlation_id = :correlationId")
        }
        val q = handle.createQuery(sql.toString()).bind("tenantId", query.tenantId)
        if (query.templateId != null) {
            q.bind("templateId", query.templateId)
        }
        if (query.correlationId != null) {
            q.bind("correlationId", query.correlationId)
        }
        q.mapTo<Long>().one()
    }
}

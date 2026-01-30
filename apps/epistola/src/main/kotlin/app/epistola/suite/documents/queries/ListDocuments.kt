package app.epistola.suite.documents.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Query to list documents for a tenant.
 *
 * @property tenantId Tenant that owns the documents
 * @property templateId Optional filter by template ID
 * @property limit Maximum number of results (default: 50)
 * @property offset Pagination offset (default: 0)
 */
data class ListDocuments(
    val tenantId: Long,
    val templateId: Long? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<DocumentMetadata>>

@Component
class ListDocumentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDocuments, List<DocumentMetadata>> {

    override fun handle(query: ListDocuments): List<DocumentMetadata> = jdbi.withHandle<List<DocumentMetadata>, Exception> { handle ->
        val sql = StringBuilder(
            """
            SELECT id, tenant_id, template_id, variant_id, version_id,
                   filename, content_type, size_bytes,
                   created_at, created_by
            FROM documents
            WHERE tenant_id = :tenantId
            """
        )

        if (query.templateId != null) {
            sql.append(" AND template_id = :templateId")
        }

        sql.append(" ORDER BY created_at DESC")
        sql.append(" LIMIT :limit OFFSET :offset")

        val q = handle.createQuery(sql.toString())
            .bind("tenantId", query.tenantId)
            .bind("limit", query.limit)
            .bind("offset", query.offset)

        if (query.templateId != null) {
            q.bind("templateId", query.templateId)
        }

        q.map { rs, _ ->
            DocumentMetadata(
                id = rs.getLong("id"),
                tenantId = rs.getLong("tenant_id"),
                templateId = rs.getLong("template_id"),
                variantId = rs.getLong("variant_id"),
                versionId = rs.getLong("version_id"),
                filename = rs.getString("filename"),
                contentType = rs.getString("content_type"),
                sizeBytes = rs.getLong("size_bytes"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                createdBy = rs.getString("created_by")
            )
        }
            .list()
    }
}

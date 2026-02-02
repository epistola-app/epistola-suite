package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Query to list documents for a tenant.
 *
 * @property tenantId Tenant that owns the documents
 * @property templateId Optional filter by template ID
 * @property correlationId Optional filter by client-provided correlation ID
 * @property limit Maximum number of results (default: 50)
 * @property offset Pagination offset (default: 0)
 */
data class ListDocuments(
    val tenantId: TenantId,
    val templateId: TemplateId? = null,
    val correlationId: String? = null,
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
                   filename, correlation_id, content_type, size_bytes,
                   created_at, created_by
            FROM documents
            WHERE tenant_id = :tenantId
            """,
        )

        if (query.templateId != null) {
            sql.append(" AND template_id = :templateId")
        }

        if (query.correlationId != null) {
            sql.append(" AND correlation_id = :correlationId")
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

        if (query.correlationId != null) {
            q.bind("correlationId", query.correlationId)
        }

        q.map { rs, _ ->
            DocumentMetadata(
                id = DocumentId(rs.getObject("id", UUID::class.java)),
                tenantId = TenantId(rs.getString("tenant_id")),
                templateId = TemplateId(rs.getObject("template_id", UUID::class.java)),
                variantId = VariantId(rs.getObject("variant_id", UUID::class.java)),
                versionId = VersionId(rs.getObject("version_id", UUID::class.java)),
                filename = rs.getString("filename"),
                correlationId = rs.getString("correlation_id"),
                contentType = rs.getString("content_type"),
                sizeBytes = rs.getLong("size_bytes"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                createdBy = rs.getString("created_by"),
            )
        }
            .list()
    }
}

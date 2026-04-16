package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    val tenantId: TenantKey,
    val templateId: TemplateKey? = null,
    val correlationId: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<DocumentMetadata>>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

@Component
class ListDocumentsHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDocuments, List<DocumentMetadata>> {

    override fun handle(query: ListDocuments): List<DocumentMetadata> = jdbi.withHandle<List<DocumentMetadata>, Exception> { handle ->
        val sql = StringBuilder(
            """
            SELECT id, tenant_key, catalog_key, template_key, variant_key, version_key,
                   filename, correlation_id, content_type, size_bytes,
                   created_at, created_by
            FROM documents
            WHERE tenant_key = :tenantId
            """,
        )

        if (query.templateId != null) {
            sql.append(" AND template_key = :templateId")
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
                id = DocumentKey(rs.getObject("id", UUID::class.java)),
                tenantId = TenantKey(rs.getString("tenant_key")),
                catalogKey = CatalogKey(rs.getString("catalog_key")),
                templateId = TemplateKey(rs.getString("template_key")),
                variantId = VariantKey(rs.getString("variant_key")),
                versionId = VersionKey(rs.getInt("version_key")),
                filename = rs.getString("filename"),
                correlationId = rs.getString("correlation_id"),
                contentType = rs.getString("content_type"),
                sizeBytes = rs.getLong("size_bytes"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                createdBy = rs.getObject("created_by", UUID::class.java)?.let { UserKey(it) },
            )
        }
            .list()
    }
}

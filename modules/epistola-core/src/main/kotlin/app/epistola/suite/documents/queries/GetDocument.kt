package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.model.Document
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Query to get a document's metadata.
 *
 * Binary content is stored in the [ContentStore] and should be retrieved
 * via [ContentKey.document] at the serving layer.
 *
 * @property tenantId Tenant that owns the document
 * @property documentId The document ID
 */
data class GetDocument(
    val tenantId: TenantKey,
    val documentId: DocumentKey,
) : Query<Document?>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_VIEW
    override val tenantKey get() = tenantId
}

@Component
class GetDocumentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDocument, Document?> {

    override fun handle(query: GetDocument): Document? = jdbi.withHandle<Document?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_key, catalog_key, template_key, variant_key, version_key,
                   filename, correlation_id, content_type, size_bytes,
                   created_at, created_by
            FROM documents
            WHERE id = :documentId
              AND tenant_key = :tenantId
            """,
        )
            .bind("documentId", query.documentId)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                Document(
                    id = DocumentKey(rs.getObject("id", UUID::class.java)),
                    tenantKey = TenantKey(rs.getString("tenant_key")),
                    catalogKey = CatalogKey(rs.getString("catalog_key")),
                    templateKey = TemplateKey(rs.getString("template_key")),
                    variantKey = VariantKey(rs.getString("variant_key")),
                    versionKey = VersionKey(rs.getInt("version_key")),
                    filename = rs.getString("filename"),
                    correlationId = rs.getString("correlation_id"),
                    contentType = rs.getString("content_type"),
                    sizeBytes = rs.getLong("size_bytes"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java)?.let { UserKey(it) },
                )
            }
            .findOne()
            .orElse(null)
    }
}

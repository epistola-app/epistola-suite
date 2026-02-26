package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Document metadata without content (for efficient listing).
 */
data class DocumentMetadata(
    val id: DocumentKey,
    val tenantId: TenantKey,
    val templateId: TemplateKey,
    val variantId: VariantKey,
    val versionId: VersionKey,
    val filename: String,
    val correlationId: String?,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: OffsetDateTime,
    val createdBy: UserKey?,
)

/**
 * Query to get document metadata without loading the full content.
 *
 * @property tenantId Tenant that owns the document
 * @property documentId The document ID
 */
data class GetDocumentMetadata(
    val tenantId: TenantKey,
    val documentId: DocumentKey,
) : Query<DocumentMetadata?>

@Component
class GetDocumentMetadataHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDocumentMetadata, DocumentMetadata?> {

    override fun handle(query: GetDocumentMetadata): DocumentMetadata? = jdbi.withHandle<DocumentMetadata?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, template_id, variant_id, version_id,
                   filename, correlation_id, content_type, size_bytes,
                   created_at, created_by
            FROM documents
            WHERE id = :documentId
              AND tenant_id = :tenantId
            """,
        )
            .bind("documentId", query.documentId)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
                DocumentMetadata(
                    id = DocumentKey(rs.getObject("id", UUID::class.java)),
                    tenantId = TenantKey(rs.getString("tenant_id")),
                    templateId = TemplateKey(rs.getString("template_id")),
                    variantId = VariantKey(rs.getString("variant_id")),
                    versionId = VersionKey(rs.getInt("version_id")),
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

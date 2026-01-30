package app.epistola.suite.documents.queries

import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime

/**
 * Document metadata without content (for efficient listing).
 */
data class DocumentMetadata(
    val id: Long,
    val tenantId: Long,
    val templateId: Long,
    val variantId: Long,
    val versionId: Long,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val createdAt: OffsetDateTime,
    val createdBy: String?,
)

/**
 * Query to get document metadata without loading the full content.
 *
 * @property tenantId Tenant that owns the document
 * @property documentId The document ID
 */
data class GetDocumentMetadata(
    val tenantId: Long,
    val documentId: Long,
) : Query<DocumentMetadata?>

@Component
class GetDocumentMetadataHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDocumentMetadata, DocumentMetadata?> {

    override fun handle(query: GetDocumentMetadata): DocumentMetadata? = jdbi.withHandle<DocumentMetadata?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, template_id, variant_id, version_id,
                   filename, content_type, size_bytes,
                   created_at, created_by
            FROM documents
            WHERE id = :documentId
              AND tenant_id = :tenantId
            """
        )
            .bind("documentId", query.documentId)
            .bind("tenantId", query.tenantId)
            .map { rs, _ ->
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
            .findOne()
            .orElse(null)
    }
}

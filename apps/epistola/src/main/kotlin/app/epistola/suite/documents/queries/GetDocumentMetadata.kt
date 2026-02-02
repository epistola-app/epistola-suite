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
 * Document metadata without content (for efficient listing).
 */
data class DocumentMetadata(
    val id: DocumentId,
    val tenantId: TenantId,
    val templateId: TemplateId,
    val variantId: VariantId,
    val versionId: VersionId,
    val filename: String,
    val correlationId: String?,
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
    val tenantId: TenantId,
    val documentId: DocumentId,
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
                    id = DocumentId(rs.getObject("id", UUID::class.java)),
                    tenantId = TenantId(rs.getString("tenant_id")),
                    templateId = TemplateId(rs.getString("template_id")),
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
            .findOne()
            .orElse(null)
    }
}

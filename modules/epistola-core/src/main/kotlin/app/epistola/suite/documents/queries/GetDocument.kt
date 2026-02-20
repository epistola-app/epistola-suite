package app.epistola.suite.documents.queries

import app.epistola.suite.common.ids.DocumentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.documents.model.Document
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
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
    val tenantId: TenantId,
    val documentId: DocumentId,
) : Query<Document?>

@Component
class GetDocumentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDocument, Document?> {

    override fun handle(query: GetDocument): Document? = jdbi.withHandle<Document?, Exception> { handle ->
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
                Document(
                    id = DocumentId(rs.getObject("id", UUID::class.java)),
                    tenantId = TenantId(rs.getString("tenant_id")),
                    templateId = TemplateId(rs.getString("template_id")),
                    variantId = VariantId(rs.getString("variant_id")),
                    versionId = VersionId(rs.getInt("version_id")),
                    filename = rs.getString("filename"),
                    correlationId = rs.getString("correlation_id"),
                    contentType = rs.getString("content_type"),
                    sizeBytes = rs.getLong("size_bytes"),
                    createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
                    createdBy = rs.getObject("created_by", UUID::class.java)?.let { UserId(it) },
                )
            }
            .findOne()
            .orElse(null)
    }
}

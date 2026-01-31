package app.epistola.suite.documents.queries

import app.epistola.suite.documents.model.Document
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Query to get a document with its full content.
 *
 * @property tenantId Tenant that owns the document
 * @property documentId The document ID
 */
data class GetDocument(
    val tenantId: Long,
    val documentId: Long,
) : Query<Document?>

@Component
class GetDocumentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetDocument, Document?> {

    override fun handle(query: GetDocument): Document? = jdbi.withHandle<Document?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT id, tenant_id, template_id, variant_id, version_id,
                   filename, content_type, size_bytes, content,
                   created_at, created_by
            FROM documents
            WHERE id = :documentId
              AND tenant_id = :tenantId
            """,
        )
            .bind("documentId", query.documentId)
            .bind("tenantId", query.tenantId)
            .mapTo<Document>()
            .findOne()
            .orElse(null)
    }
}

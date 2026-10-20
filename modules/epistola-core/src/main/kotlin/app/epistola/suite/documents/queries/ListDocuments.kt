// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents.queries

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.DocumentKey
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantKey
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
 * @property catalog Optional filter by the catalog the documents were rendered from
 * @property limit Maximum number of results (default: 50)
 * @property offset Pagination offset (default: 0)
 */
data class ListDocuments(
    val tenantId: TenantKey,
    val templateId: TemplateKey? = null,
    val correlationId: String? = null,
    val catalog: DocumentCatalog = DocumentCatalog(),
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
            SELECT id, tenant_key, catalog_key, registry, namespace, template_key, variant_key, release_version, draft_id, draft_label,
                   filename, correlation_id, content_type, size_bytes,
                   created_at, created_by
            FROM documents
            WHERE tenant_key = :tenantId
            """,
        )

        if (query.templateId != null) {
            sql.append(DOCUMENTS_OF_TEMPLATE)
        }

        if (query.correlationId != null) {
            sql.append(" AND correlation_id = :correlationId")
        }
        sql.append(query.catalog.sql)

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
        query.catalog.bindTo(q)

        q.map { rs, _ ->
            DocumentMetadata(
                id = DocumentKey(rs.getObject("id", UUID::class.java)),
                tenantId = TenantKey(rs.getString("tenant_key")),
                catalogKey = CatalogKey(rs.getString("catalog_key")),
                registry = rs.getString("registry"),
                namespace = rs.getString("namespace"),
                templateId = TemplateKey(rs.getString("template_key")),
                variantId = VariantKey(rs.getString("variant_key")),
                releaseVersion = rs.getString("release_version"),
                draftId = rs.getString("draft_id"),
                draftLabel = rs.getString("draft_label"),
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

/**
 * Generation history belongs to a template by identity, not only by address. A document generated
 * before its template was renamed still records the old key, but its `template_resource_id` (filled
 * on insert) is the template's own, so it is found through the key the template has now.
 *
 * Rows written before that column existed are deliberately not backfilled -- doing so would have
 * meant rewriting every partition of the largest table in the product at upgrade time. They match
 * by key alone, which is also what keeps a bare-key lookup meaning what it did before relocation
 * existed, and the gap closes by itself: partitions are monthly and retention is measured in
 * months, so every surviving row carries the identity within one window.
 *
 * The key a template has now is the one in its catalog's current version.
 */

/**
 * The catalog documents are listed for, by the coordinates stored with each document: each part
 * null to match any. A template key is unique only within its catalog, so a listing by template
 * names the catalog too (contract `listDocuments`).
 */
data class DocumentCatalog(
    val registry: String? = null,
    val namespace: String? = null,
    val catalogKey: CatalogKey? = null,
) {
    internal val sql: String
        get() = buildString {
            if (registry != null) append(" AND registry = :registry")
            if (namespace != null) append(" AND namespace = :namespace")
            if (catalogKey != null) append(" AND catalog_key = :catalogKey")
        }

    internal fun bindTo(query: org.jdbi.v3.core.statement.Query) {
        registry?.let { query.bind("registry", it) }
        namespace?.let { query.bind("namespace", it) }
        catalogKey?.let { query.bind("catalogKey", it) }
    }
}

internal val DOCUMENTS_OF_TEMPLATE = """
    AND (template_key = :templateId
         OR template_resource_id IN (
             SELECT dt.resource_id FROM document_templates dt
             WHERE dt.tenant_key = :tenantId AND dt.id = :templateId AND ${inCurrentVersion("dt")}))
"""

// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.TEMPLATE_COLUMNS
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Sortable columns for [ListDocumentTemplates]. The [orderBy] expressions are a fixed
 * whitelist baked into the SQL — never interpolate raw user input into ORDER BY. The
 * [param] wire values are exactly the sortable `TemplateSummaryDto` response fields, so
 * a caller sorts by the same names it reads back.
 *
 * Deliberately separate from [TemplateSort] (the UI list's whitelist on the same
 * `document_templates` table): the two surfaces expose different sort keys — REST sorts
 * by the `TemplateSummaryDto` response fields (incl. `createdAt`), the UI sorts by its
 * richer list columns (`catalog`, `variants`). They are allowed to diverge.
 *
 * `name` sorts by `LOWER(name)` so the order is case-insensitive regardless of the
 * cluster's `LC_COLLATE`, matching the case-insensitive `ILIKE` search filter in the
 * same query (a `C`-collation cluster would otherwise sort all uppercase before any
 * lowercase).
 *
 * @property param The stable query-parameter value used by the REST API.
 * @property orderBy The whitelisted SQL ORDER BY expression for this sort key.
 */
enum class DocumentTemplateSort(
    val param: String,
    val orderBy: String,
) {
    NAME("name", "LOWER(dt.name)"),
    CREATED("createdAt", "dt.created_at"),
    UPDATED("lastModified", "dt.updated_at"),
    ;

    companion object {
        /** The supported wire values, for enumerating in a rejection when an unknown key is supplied. */
        val paramValues: List<String> = entries.map { it.param }

        /**
         * Case-insensitive strict lookup: returns `null` for an unrecognized value rather than
         * silently falling back. The REST layer maps a non-null-but-unknown `sort` to a 400 (the
         * contract does no validation of its own), while an absent `sort` selects the default order.
         */
        fun fromParamOrNull(param: String): DocumentTemplateSort? = entries.find { it.param.equals(param, ignoreCase = true) }
    }
}

data class ListDocumentTemplates(
    val tenantId: TenantId,
    val catalog: CatalogId? = null,
    val searchTerm: String? = null,
    val sort: DocumentTemplateSort = DocumentTemplateSort.UPDATED,
    val descending: Boolean = true,
    val limit: Int = 50,
    val offset: Int = 0,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<List<DocumentTemplate>>,
    RequiresPermission {
    init {
        validate("catalog", catalog == null || catalog.tenantId == tenantId) { "The catalog is of the tenant" }
    }

    val catalogKey: CatalogKey? get() = catalog?.key
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ListDocumentTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDocumentTemplates, List<DocumentTemplate>> {
    override fun handle(query: ListDocumentTemplates): List<DocumentTemplate> = jdbi.withHandle<List<DocumentTemplate>, Exception> { handle ->
        val sql = buildString {
            // The view holds each catalog's version a caller means, a draft's or a release's.
            append("SELECT $TEMPLATE_COLUMNS FROM current_templates dt WHERE dt.tenant_key = :tenantId")
            if (query.catalogKey != null) {
                append(" AND dt.catalog_id = ${catalogAt("tenantId", "catalogKey")}")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm")
            }
            // ORDER BY expression comes from the DocumentTemplateSort whitelist, never raw input.
            // The (catalog key, id) tiebreaker is a total order: a catalog key is unique in its
            // tenant and a template id in its catalog, so paging stays stable even when the sort
            // column ties and even for callers that list across catalogs (catalogKey == null).
            val direction = if (query.descending) "DESC" else "ASC"
            append(" ORDER BY ${query.sort.orderBy} $direction, catalog_key ASC, dt.id ASC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bindCatalog("catalogKey", query.catalog)
        }
        if (!query.searchTerm.isNullOrBlank()) {
            jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
        }
        jdbiQuery
            .bind("limit", query.limit)
            .bind("offset", query.offset)
            .mapTo<DocumentTemplate>()
            .list()
    }
}

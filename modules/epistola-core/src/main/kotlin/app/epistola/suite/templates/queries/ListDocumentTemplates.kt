// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.queries

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.DocumentTemplate
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
    val searchTerm: String? = null,
    val catalogKey: CatalogKey? = null,
    val sort: DocumentTemplateSort = DocumentTemplateSort.UPDATED,
    val descending: Boolean = true,
    val limit: Int = 50,
    val offset: Int = 0,
) : Query<List<DocumentTemplate>>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
    override val tenantKey: TenantKey get() = tenantId.key
}

@Component
class ListDocumentTemplatesHandler(
    private val jdbi: Jdbi,
) : QueryHandler<ListDocumentTemplates, List<DocumentTemplate>> {
    override fun handle(query: ListDocumentTemplates): List<DocumentTemplate> = jdbi.withHandle<List<DocumentTemplate>, Exception> { handle ->
        val sql = buildString {
            append("SELECT dt.id, dt.tenant_key, dt.catalog_key, c.type AS catalog_type, dt.name, dt.theme_key, dt.theme_catalog_key, dt.pdfa_enabled, dt.created_at, dt.updated_at, dt.created_by, dt.updated_by FROM document_templates dt JOIN catalogs c ON c.tenant_key = dt.tenant_key AND c.id = dt.catalog_key WHERE dt.tenant_key = :tenantId")
            if (query.catalogKey != null) {
                append(" AND dt.catalog_key = :catalogKey")
            }
            if (!query.searchTerm.isNullOrBlank()) {
                append(" AND dt.name ILIKE :searchTerm")
            }
            // ORDER BY expression comes from the DocumentTemplateSort whitelist, never raw input.
            // The (catalog_key, id) tiebreaker is a total order for the composite PK
            // (tenant_key, catalog_key, id), so paging stays stable even when the sort
            // column ties and even for callers that list across catalogs (catalogKey == null).
            val direction = if (query.descending) "DESC" else "ASC"
            append(" ORDER BY ${query.sort.orderBy} $direction, dt.catalog_key ASC, dt.id ASC")
            append(" LIMIT :limit OFFSET :offset")
        }

        val jdbiQuery = handle.createQuery(sql)
            .bind("tenantId", query.tenantId.key)
        if (query.catalogKey != null) {
            jdbiQuery.bind("catalogKey", query.catalogKey)
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
